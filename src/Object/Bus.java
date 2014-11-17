package Object;

import java.util.*;

/**
 * Created by Bo on 11/6/14.
 */
public class Bus {

    static final int BUS_READ = 1;
    static final int BUS_READEX = 2;
    static final int BUS_FLUSH = 3;
    static final int BUS_ACCESS_MEMORY_WRITE = 4;
    static final int BUS_ACCESS_MEMORY_READ = 5;

    static int numOfProc;
    static int dataOnBus;
    static boolean isBlocked;
    static boolean isBusTransactionComplete;
    static Vector<Processor> processors;
    static Queue<List<OperationPair>> busOperations;
    static List<OperationPair> cycleOps;
    static int expectedTerminationCycle;

    static BusLine busLine; //where cache snoops, check for results

    public static void initBus(Vector<Processor> procs) {
        busOperations = new LinkedList<List<OperationPair>>();
        processors = procs;
        cycleOps = new ArrayList<OperationPair>();
        numOfProc = procs.size();
        expectedTerminationCycle = -1;
        busLine = new BusLine();
    }

    public static void updateCacheAfterMemAccess() {
        isBusTransactionComplete = true;
        Cache cache = processors.get(busLine.getReceiverProcessorNumberOnBus()).getCache();
        cache.respondFromAccessMemory(busLine.getCurrOpAddresOnBus());
    }

//    public static void read(OperationPair op, int currCycle) {
//        String address = op.getAddress();
//        int currProcessor = op.getInitiatorNumber();
//        Cache cache;
//        boolean isResultExist = false;
//        for (int i = 0; i < numOfProc; i++) {
//            if(i == currProcessor)
//                continue;
//
//            cache = processors.get(i).getCache();
//            int data = cache.busRead(address, currCycle);
//            if(data != 0 && !isResultExist) {
//                dataOnBus++;
//                isResultExist = true;
//            }
//
//        }
////        expectedTerminationCycle = currCycle + 1;
//        isBlocked = false;
//        busLine.setBusLine(new OperationPair(BUS_READ,currProcessor, address, currCycle), isResultExist);
//    }

//    public static void readEx(OperationPair op) {
//        String address = op.getAddress();
//        int currProcessor = op.getInitiatorNumber();
//        Cache cache;
//        boolean isResultExist = false;
//        for (int i = 0; i < numOfProc; i++) {
//            if(i == currProcessor)
//                continue;
//
//            cache = processors.get(i).getCache();
//            int data = cache.busReadEx(address);
//            if(data != 0 && !isResultExist) {
//                dataOnBus++;
//                isResultExist = true;
//            }
//        }
//        cache = processors.get(currProcessor).getCache();
////        cache.respondFromBusReadEx(isResultExist);
////        return isResultExist ? 1 : 0;
//    }

    public static void accessMemory(int currCycle) {
        //+10 cycles
        expectedTerminationCycle = currCycle + 10;
        isBlocked = true;
    }

    public static int getDataOnBus() {
        return dataOnBus;
    }

    public static void insertTransactionOnBus(OperationPair op ) {
        cycleOps.add(op);
    }

    public static boolean isBusBlocked() {
        return isBlocked;
    }

    public static boolean checkBusBlock(int currCycle) {
        if(currCycle == (expectedTerminationCycle-1)) {
            updateCacheAfterMemAccess(); // at the last cycle update the caches
            return isBlocked = true;
        } else if(currCycle != expectedTerminationCycle &&
                expectedTerminationCycle != -1) return isBlocked = true;
        else{
            expectedTerminationCycle = -1;
            return isBlocked = false;
        }
    }

    public static void executeBusTransactions(int currCycle) {
        if(!cycleOps.isEmpty()) {
            busOperations.add(cycleOps);
            cycleOps.clear();
        }

        if(!busOperations.isEmpty() && !checkBusBlock(currCycle)) {
            OperationPair op;
            List<OperationPair> busCycleOps = busOperations.peek();
            int index = random(busCycleOps.size()-1);
            if(busCycleOps.size() > 1) {
                op = busCycleOps.get(index);
                busCycleOps.remove(index);
            } else
                op = busOperations.poll().get(0);

            if(op.getOpsNumber() == BUS_ACCESS_MEMORY_WRITE ||
                    op.getOpsNumber() == BUS_ACCESS_MEMORY_READ ||
                    op.getOpsNumber() == BUS_FLUSH)
                accessMemory(currCycle);

            busLine.setBusLine(op, false);

        }else {
            System.out.println("bus block: "+isBusBlocked()+"  ops count: "+busOperations.size());
        }
    }

    static int random(int size) {
        Random r = new Random();
        return r.nextInt(size+1);
    }

    public static void main(String[] args) {
        System.out.println(Bus.random(2-1));
    }

}

class OperationPair {
    private int cycleNum;
    private int opsNumber;
    private int initiatorNumber;
    private String address;

    public OperationPair(int ops, int initNum, String address, int cycleNum) {
        this.opsNumber = ops;
        this.initiatorNumber = initNum;
        this.address = address;
        this.cycleNum = cycleNum;
    }

    public int getOpsNumber() {
        return opsNumber;
    }

    public int getInitiatorNumber() {
        return initiatorNumber;
    }

    public String getAddress() {
        return address;
    }
}

class BusLine {
    private OperationPair currServingOperation;
    private boolean result;

    public BusLine() {}

    public void setBusLine(OperationPair op, boolean result) {
        currServingOperation = op;
        this.result = result;
    }

    public int getReceiverProcessorNumberOnBus() {
        return currServingOperation.getInitiatorNumber();
    }

    public boolean getResultOnBus() {
        return result;
    }

    public String getCurrOpAddresOnBus() {
        return currServingOperation.getAddress();
    }

    public void setResult(boolean result) {
        this.result = result;
    }

    public OperationPair getOp() {
        return currServingOperation;
    }
}
