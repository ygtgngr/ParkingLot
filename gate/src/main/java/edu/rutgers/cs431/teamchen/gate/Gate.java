package edu.rutgers.cs431.teamchen.gate;


import com.sun.net.httpserver.HttpServer;
import edu.rutgers.cs431.TrafficGeneratorProto.Car;
import edu.rutgers.cs431.teamchen.gate.token.DistributedTokenStore;
import edu.rutgers.cs431.teamchen.gate.token.NoShareTokenStore;
import edu.rutgers.cs431.teamchen.gate.token.TokenStore;
import edu.rutgers.cs431.teamchen.proto.CarWithToken;
import edu.rutgers.cs431.teamchen.proto.GateRegisterRequest;
import edu.rutgers.cs431.teamchen.proto.GateRegisterResponse;
import edu.rutgers.cs431.teamchen.util.DataFormatter;
import edu.rutgers.cs431.teamchen.util.GateAddressBook;
import edu.rutgers.cs431.teamchen.util.SyncClock;
import edu.rutgers.cs431.teamchen.util.SystemConfig;

import java.io.IOException;
import java.net.*;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Gate implements Runnable {

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
    private final GateAddressBook gateAddressBook = new GateAddressBook();
    private SyncClock clock;
    private TokenStore tokenStore;
    private ParkingSpaceConnection parkingSpaceConn;
    private volatile long totalWaitingTime = 0L;
    private volatile int carsProcessedCount = 0;
    private HttpServer httpServer;
    private ServerSocket carsAcceptor;

    public Gate(String monitorHttpAddr, int gatePort, int httpPort, long tranferDuration, String trafGenAddr, int
            trafGenPort) {
        this.waitingQueue = new ConcurrentLinkedQueue<CarArrival>();
        this.gateTcpPort = gatePort;
        this.gateHttpPort = httpPort;
        this.transferDuration = tranferDuration;

        // set up the time service
        try {
            this.clock = new SyncClock(trafGenAddr, trafGenPort);
        } catch (IOException e) {
            reportError("unable to set up clock synchronization: " + e.getMessage());
            System.exit(1);
        }

        MonitorConnection mc = null;
        try {
            mc = new MonitorConnection(monitorHttpAddr);
        } catch (MalformedURLException e) {
            reportError("received a malformed monitor URL: " + monitorHttpAddr + ": " + e.getMessage());
            System.exit(1);
        }
        this.monitorConn = mc;
    }

    private static void reportError(String msg) {
        System.err.println("WARNING: " + msg);
    }

    private static void log(String msg) {
        System.out.println("INFO: " + msg);
    }

    public long getTotalWaitingTime() {
        return totalWaitingTime;
    }

    public int getCarsProcessedCount() {
        return carsProcessedCount;
    }

    // registers with the monitor then sets up the state in order to start processing
    public void registerThenInit() {
        GateRegisterRequest req = null;
        try {
            req = new GateRegisterRequest(
                    InetAddress.getLocalHost().getHostName(), this.gateTcpPort, this.gateHttpPort);
        } catch (UnknownHostException e) {
            reportError("can't identify localhost: " + e.getMessage());
            System.exit(1);
        }
        GateRegisterResponse resp = null;
        try {
            resp = this.monitorConn.registersGate(req);
        } catch (RuntimeException e) {
            reportError("unable to register this Gate: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            reportError("unable to register this Gate: " + e.getMessage());
            System.exit(1);
        }

        try {
            this.parkingSpaceConn = new ParkingSpaceConnection(resp.parkingSpaceHttpUrl);
        } catch (IOException e) {
            reportError("invalid Parking Space's HTTP URL: " + resp.parkingSpaceHttpUrl + ": " + e.getMessage());
            System.exit(1);
        }

        // set up the token distribution strategy
        switch (resp.strategy) {
            case GateRegisterResponse.STRATEGY_NO_SHARED:
                this.tokenStore = new NoShareTokenStore(resp.tokens);
                break;
            case GateRegisterResponse.STRATEGY_DISTRIBUTED:
                this.tokenStore = new DistributedTokenStore(resp.tokens, gateAddressBook, this.httpServer);
                break;
        }
    }

    // starts an http server
    public void http() {
        try {
            httpServer = HttpServer.create();
            httpServer.bind(new InetSocketAddress("localhost", this.gateHttpPort), SystemConfig
                    .MAXIMUM_HTTP_CONNECTIONS/**/);
        } catch (IOException e) {
            reportError("unable to create the http service for gate: " + e.getMessage());
            System.exit(1);
        }
        httpServer.createContext(SystemConfig.GATE_GET_STATS_PATH, new GateStatsHttpHandler(this));
        httpServer.createContext(SystemConfig.GATE_PEER_ADDRESS_CHANGE_PATH, this.gateAddressBook);
        httpServer.createContext(SystemConfig.GATE_CAR_LEAVING_PATH, new CarLeavingHttpHandler(this));
        httpServer.start();
    }

    // initiates a thread that listens to traffic generator(s?)
    public void tcpListensToTrafficGens() {
        // Creates a car accepting socket
        log("Starting to accept cars from traffic generator...");

        try {
            this.carsAcceptor = new ServerSocket();
            this.carsAcceptor.bind(new InetSocketAddress("localhost", this.gateTcpPort));
            new Thread(() -> this.acceptsGeneratorCarStreams(this.carsAcceptor)).start();
        } catch (IOException e) {
            reportError("unable to set up a car accepting socket: " + e.getMessage());
            System.exit(1);
        }

    }

    // actively listens on the carsAcceptor, and expects a new car stream from
    // a traffic generator
    private void acceptsGeneratorCarStreams(ServerSocket gateSocket) {
        final Gate gate = this;
        Socket carStream = null;
        while (true) {
            try {
                carStream = gateSocket.accept();
                Car car = Car.parseDelimitedFrom(carStream.getInputStream());
                if (car == null) {
                    return; // no longer be able to receive car from this socket
                }
                log("(TrafficGenerator->Gate): " + DataFormatter.format(car));
                gate.queueIn(car);
            } catch (Exception e) {
                reportError("Problem accepting a new car stream: " + e.getMessage());
                break;
            } finally {
                try {
                    carStream.close();
                } catch (IOException e) {
                    reportError("Problem closing traffic generator socket: " + e.getMessage());
                }
            }
        }
        try {
            gateSocket.close();
        } catch (IOException e) {
            reportError("Unable to close the gate TCP server socket");
        }
    }

    public void onCarLeaving(CarWithToken cwt) {
        log("(Gate -> __Traffic__) " + DataFormatter.format(cwt));
        this.tokenStore.addToken(cwt.token);
    }

    // add a car to the waiting queue
    private void queueIn(Car car) {
        long arrivalTime = 0L;
        arrivalTime = this.clock.getTime();

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
            } catch (InterruptedException e) {
                reportError("getting token is interrupted: " + e.getMessage());
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
        long passedGateTime = this.clock.getTime() + this.transferDuration;
        while (this.clock.getTime() < passedGateTime) {
            continue;
        }
        try {
            this.parkingSpaceConn.sendCarToParkingSpace(cwt);
            log("(Gate->ParkingSpace) " + DataFormatter.format(cwt));
        } catch (IOException e) {
            reportError("unable to send car with token " + cwt.token + " to the parking space: " + e.getMessage());
            log("Returning token " + cwt.token + " back to the storage");
            this.tokenStore.addToken(cwt.token);
        }
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
        log("HTTP Service is up at " + httpServer.getAddress().toString() + ".");
        this.tcpListensToTrafficGens(); // listens for traffic generator car stream on a TCP/IP socket
        log("Listening to Traffic Generator at " + this.carsAcceptor.getLocalSocketAddress().toString());
        this.registerThenInit(); // registers this gate to the monitor
        log("Gate registered to the monitor.");
        log("Start processing cars... ");
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
