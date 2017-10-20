package edu.rutgers.cs431.teamchen.gate;


import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import edu.rutgers.cs431.TrafficGeneratorProto.Car;
import edu.rutgers.cs431.teamchen.SyncClock;
import edu.rutgers.cs431.teamchen.proto.CarWithToken;
import edu.rutgers.cs431.teamchen.proto.GateRegisterRequest;
import edu.rutgers.cs431.teamchen.proto.GateRegisterResponse;

public class Gate implements Runnable, PeerHttpAddressProvider {

    private static final Logger logger = Logger.getLogger("Gate");
    // port to listen to cars from traffic generator
    private final int gateTcpPort;
    // port to listen to http requests
    private final int gateHttpPort;
    // D the cost to transfer the car to the parking lot
    private final long transferDuration;
    // the carsAcceptor's waiting queue
    private final Queue<CarArrival> waitingQueue;
    private final Lock waitingQLock = new ReentrantLock();
    private final Condition queueNotEmpty = waitingQLock.newCondition();
    private final MonitorConnection monitorConn;
    private final Lock peerHttpAddrsLock = new ReentrantLock();
    private SyncClock clock;
    private TokenStore tokenStore;
    private ArrayList<URL> peerHttpAddrs;
    private volatile long totalWaitingTime = 0L;
    private volatile long carsProcessedCount = 0L;

    public Gate(String monitorAddr, int monitorPort, int gatePort, int httpPort, long tranferDuration) {
        this.waitingQueue = (LinkedList<CarArrival>) Collections.synchronizedList(new LinkedList<CarArrival>());
        this.gateTcpPort = gatePort;
        this.gateHttpPort = httpPort;
        this.transferDuration = tranferDuration;
        this.monitorConn = new MonitorConnection(monitorAddr, monitorPort);
    }

    private static void reportError(String msg) {
        logger.warning(msg);
    }

    private static void log(String msg) {
        logger.info(msg);
    }

    public ArrayList<URL> getPeerHttpAddresses() {
        ArrayList<URL> res = null;
        peerHttpAddrsLock.lock();
        res = peerHttpAddrs;
        peerHttpAddrsLock.unlock();
        return res;
    }

    public void setPeerHttpAddresses(ArrayList<URL> peerAddrs) {
        peerHttpAddrsLock.lock();
        this.peerHttpAddrs = peerAddrs;
        peerHttpAddrsLock.unlock();
    }

    // registers with the monitor then sets up the state in order to start processing
    public void registerThenInit() {
        GateRegisterRequest req = new GateRegisterRequest(this.gateTcpPort, this.gateHttpPort);
        GateRegisterResponse resp = this.monitorConn.registersGate(req);

        // set up the time service
        Socket trafGen = null;
        try {
            trafGen = new Socket(resp.trafficGeneratorAddr, resp.trafficGeneratorPort);
        } catch (IOException e) {
            reportError("Unable to connect to the traffic generator: " + e.getMessage());
            System.exit(1);
        }
        this.clock = new SyncClock(trafGen);

        // set up the token distribution strategy
        switch (resp.strategy) {
            case GateRegisterResponse.STRATEGY_NO_SHARED:
                this.tokenStore = new NoShareTokenStore(resp.tokens);
                break;
            case GateRegisterResponse.STRATEGY_DISTRIBUTED:
                this.tokenStore = new DistributedTokenStore(resp.tokens, this);
                break;
            case GateRegisterResponse.STRATEGY_FOR_PROFIT:
                // TODO:
                this.tokenStore = new ForProfitTokenStore(resp.tokens, this);
                break;
        }
    }

    // starts an http server
    public void http() {
        // TODO: Creates an HTTP server for the gate
    }

    // initiates a thread that listens to traffic generator(s?)
    public void tcpListensToTrafficGens() {
        // Creates a car accepting socket
        log("Starting to accept cars from traffic generator...");
        ServerSocket carAcceptor = null;
        try {
            carAcceptor = new ServerSocket(gateTcpPort);
        } catch (IOException e) {
            reportError("Unable to set up a car accepting socket: " + e.getMessage());
            System.exit(1);
        }

        new Thread(this.acceptsGeneratorCarStreams(carAcceptor)).start();
    }

    // actively listens on the carsAcceptor, and expects a new car stream from
    // a traffic generator
    private Runnable acceptsGeneratorCarStreams(ServerSocket gateSocket) {
        final Gate gate = this;
        return () -> {
            while (true) {
                try (Socket carStream = gateSocket.accept()) {
                    // handles the car stream on a thread for each new traffic generator
                    new Thread(gate.makeCarStreamHandler(carStream)).start();
                } catch (Exception e) {
                    reportError("Accepting a new car stream: " + e.getMessage());
                }
            }
        };
    }

    // returns a thread that handles incoming car stream
    private Runnable makeCarStreamHandler(Socket incomingCarSocket) {
        final Gate gate = this;
        return () -> {
            while (true) {
                try {
                    Car car = Car.parseDelimitedFrom(incomingCarSocket.getInputStream());
                    gate.queueIn(car);
                } catch (IOException e) {
                    reportError("Cannot receive car from traffic generator: " + e.getMessage());
                    return;
                }
            }
        };
    }

    // add a car to the waiting queue
    private void queueIn(Car car) {
        long arrivalTime = 0L;
        try {
            arrivalTime = this.clock.getTime();
        } catch (IOException e) {
            reportError("Unable to get the time for car arrival: " + e.getMessage());
            return;
        }

        CarArrival newArrival = new CarArrival(car, arrivalTime);
        this.waitingQLock.lock();
        this.waitingQueue.add(newArrival);
        this.queueNotEmpty.signal();
        this.waitingQLock.unlock();
    }

    // processes the car stream, removes a ready-to-depart car or assigns a token to a car,
    // waits a transferDuration, then sends the car to the parking space
    public void processCarsInQueue() {
        while (true) {
            CarArrival next = this.nextCarArrival();
            String token = null;
            long currentTime = 0L;
            try {
                currentTime = this.clock.getTime();
                if (currentTime > next.car.getDepartureTimestamp()) {
                    totalWaitingTime = currentTime - next.arrivalTime;
                    carsProcessedCount++;
                    continue;
                }
                token = this.tokenStore.getToken();
            } catch (IOException e) {
                reportError("Unable to check car departing status: " + e.getMessage());
                continue;
            } catch (InterruptedException e) {
                reportError("Getting token is interrupted: " + e.getMessage());
                continue;
            }
            totalWaitingTime = currentTime - next.arrivalTime;
            carsProcessedCount++;
            CarWithToken cwt = new CarWithToken(next.car, token);
            sendCarToParkingSpace(cwt);
        }
    }

    // waits a transferDurationTime then sends the car to the parking space.
    private void sendCarToParkingSpace(CarWithToken cwt) {
        try {
            long passedGateTime = this.clock.getTime() + this.transferDuration;
            while (this.clock.getTime() < passedGateTime) {
                continue;
            }
        } catch (IOException e) {
            reportError("Unable to get time: " + e.getMessage());
        }
        // TODO: communication with parking space using HTTP+JSON
        // Suggestion: abstract this communication layer too ?
    }

    // processes the car queue.
    // When encountering a car, the method takes it, removes from the queue for processing
    // Otherwise, it waits indefinitely til a car queues in
    private CarArrival nextCarArrival() {
        try {
            this.waitingQLock.lock();
            while (this.waitingQueue.size() == 0) {
                this.queueNotEmpty.await();
            }
            return this.waitingQueue.remove();
        } catch (InterruptedException e) {
        } finally {
            this.waitingQLock.unlock();
        }
        return null;
    }

    public void run() {
        this.http(); // http service
        this.registerThenInit(); // registers this gate to the monitor
        this.tcpListensToTrafficGens(); // listens for traffic generator car stream on a TCP/IP socket
        this.processCarsInQueue(); // runs forever as a main thread
    }

    private static class CarArrival {
        public Car car;
        public long arrivalTime;

        public CarArrival(Car car, long arrivalTime) {
            this.car = car;
            this.arrivalTime = arrivalTime;
        }
    }
}
