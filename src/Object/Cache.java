package Object;

import Protocol.MESI;
import Protocol.MSI;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Vector;

public class Cache {

    public static final int CPU_ADDRESS_SPACE = 32;
    public static final int BINARY = 2;
    public static final int BUS_READ = 99;
    public static final int BUS_READ_EXCLUSIVE = 100;
    public static final int PROCESSOR_READ = 101;
    public static final int PROCESSOR_WRITE = 102;
    public static final String LOG_PATH = "logs/Processor";

    private int cacheSize, numOfBlocks, blockSize, associativity, blockSets;
    private Vector<Vector<CacheLine>> cacheLines;

    int tagSize;        //the bound of tag, assumption the CPU has a 32 bit address size
    int blockOffset;    //the bound for offset to get the info in cache block
    int rowIndex;       //the row number (set number)

    private int protocol, cacheIdentity;
    private boolean isUniProcessor, isWaiting;
    private OperationPair currWaitingOperation;

    private int cycles, memAccess, readMiss, writeMiss, writeHit, readHit;
    private int expectedCycleToComplete; //keep track of access to mem

    private Cache cache;
    public Cache getInstance(int cacheSize, int blockSize, int associativity, int protocol, int number, boolean isUniProc) {
        if(cache == null) {
            cache = new Cache(cacheSize, blockSize, associativity, protocol, number, isUniProc);
        }
        return cache;
    }
    public Cache() {}
    public Cache(int cacheSize, int blockSize, int associativity, int protocol, int number, boolean isUniProc) {
        this.cacheSize = cacheSize * 1024;
        this.blockSize = blockSize;
        this.associativity = associativity;
        this.protocol = protocol;
        this.cacheIdentity = number;
        this.isUniProcessor = isUniProc;
        init();
    }

    private void init() {
        this.numOfBlocks = this.cacheSize / this.blockSize;
        this.blockSets = this.numOfBlocks / this.associativity;
        initCacheLines();
        blockOffset = (int) logBase2(this.blockSize);
        rowIndex = (int) logBase2(this.blockSets);
        tagSize = CPU_ADDRESS_SPACE - blockOffset - rowIndex;

        System.out.println(
                "tagsize: "+tagSize+"\tblock number: "+numOfBlocks+"\n"+
                "blockoffset: "+blockOffset+"\tassociativity: "+associativity+"\n" +
                "rowindex: "+rowIndex+"\tnum of sets: "+blockSets+"\tidentity: "+cacheIdentity);
    }

    private void initCacheLines() {
        cacheLines = new Vector<Vector<CacheLine>>();
        if(associativity > 1){
            for (int j = 0; j < blockSets; j++) {
                cacheLines.add(new Vector<CacheLine>());
                for (int i = 0; i < associativity; i++) {
                    cacheLines.get(j).add(new CacheLine(blockSize,"", MSI.STATE_INVALID));
                }
            }
        } else {
            cacheLines.add(new Vector<CacheLine>());
            for (int i = 0; i < numOfBlocks; i++) {
                cacheLines.get(0).add(new CacheLine(blockSize,"", MSI.STATE_INVALID));
            }
        }
    }

    public void snoopBus(int cycle) {
        setClockCycles(cycle);
        OperationPair op = Bus.busLine.getOp();
        if(op == null) return;
        if(Bus.busLine.getReceiverProcessorNumberOnBus() == cacheIdentity && Bus.isBusTransactionComplete) {
            if(Bus.busLine.getResultOnBus()) { //true if busRead, busReadEx reply && access mem
                updateCacheBlockAfterSnooping(op);
            } else {//no one has it, have to access mem no matter read or write
                memAccess++;
                if(op.getOpsNumber() == Bus.BUS_READ)
                    Bus.insertTransactionOnBus(new OperationPair(Bus.BUS_ACCESS_MEMORY_READ,
                        cacheIdentity, Bus.busLine.getCurrOpAddresOnBus(), cycles, Bus.BUS_READ));
                else if(op.getOpsNumber() == Bus.BUS_READEX)
                    Bus.insertTransactionOnBus(new OperationPair(Bus.BUS_ACCESS_MEMORY_WRITE,
                            cacheIdentity, Bus.busLine.getCurrOpAddresOnBus(), cycles));
            }
            Bus.busLine.setReceived();  //inform queue that the transaction is received, ready for next instruction
            if(currWaitingOperation.equals(op))
                isWaiting = false;
            System.out.println("proc "+cacheIdentity+" waiting: "+isWaiting);
        } else if(!isUniProcessor) { //not equal to the same identity and not uniproc
            if(op.getOpsNumber() == Bus.BUS_READ) executeBusRead(op);
            if(op.getOpsNumber() == Bus.BUS_READEX) executeBusReadEx(op);
        }
    }

    void updateCacheBlockAfterSnooping(OperationPair op) {
        if(op.getOpsNumber() == Bus.BUS_FLUSH) return; //don't have to care about flush
        VisitAddress addr = new VisitAddress(op.getAddress());

        Vector<CacheLine> cacheSet;
        if(associativity > 1)
            cacheSet = cacheLines.get(addr.set);
        else
            cacheSet = cacheLines.get(0);

        int state = 0;
        if(op.getOpsNumber() == Bus.BUS_READ || op.getOpsNumber() == Bus.BUS_ACCESS_MEMORY_READ)
            state = MSI.STATE_SHARED;
        else if(op.getOpsNumber() == Bus.BUS_READEX || op.getOpsNumber() == Bus.BUS_ACCESS_MEMORY_WRITE)
            state = MSI.STATE_MODIFIED;

        if(op.getOpsNumber() == Bus.BUS_ACCESS_MEMORY_READ
                && op.getPrevOps() == Bus.BUS_READ && protocol == MESI.PROTOCOL) //handles special case for mesi
            state = MESI.STATE_EXCLUSIVE;

        if(associativity == 1) { // fully associative
            CacheLine cacheLine = cacheSet.get(addr.set);
            //if cache hit then update, if not same tag, eviction, which is free
            cacheLine.updateCacheLine(addr.tag,addr.blockOffSetNumber,state);
        } else {
            int lowerestUsageCacheId = 1000000;
            CacheLine cacheLine;
            for (int i = 0; i < cacheSet.size(); i++) {
                cacheLine = cacheSet.get(i);
                if(cacheLine.getUsageCount() < lowerestUsageCacheId)
                    lowerestUsageCacheId = i;
                if(cacheLine.getTag().equals(addr.tag)) {
                    cacheLine.updateCacheLine(addr.tag,addr.blockOffSetNumber,state);
                    return;
                }
            }
            cacheLine = cacheSet.get(lowerestUsageCacheId); //LRU policy & eviction
            cacheLine.updateCacheLine(addr.tag,addr.blockOffSetNumber,state);
        }
    }

    public int processorReadCache(String address) {
        VisitAddress addr = new VisitAddress(address);
        Vector<CacheLine> cacheSet;
        if(associativity > 1)
            cacheSet = cacheLines.get(addr.set);
        else
            cacheSet = cacheLines.get(0);

        int data = readCache(cacheSet, addr.blockOffSetNumber, addr.set, addr.tag, address);
        return data;
    }

    private int readCache(Vector<CacheLine> cacheSet, int blockOffSetNumber , int modulo, String tag, String address) {
        int data = 0;
        if(associativity == 1) {
            CacheLine cacheLine = cacheSet.get(modulo);
            data = transactionsOnCacheByProcessor(cacheLine, blockOffSetNumber, PROCESSOR_READ, tag, address);
            if(data != 0) //hit
                return data;
        } else {
            int lowerestUsageCacheId = 1000000;
            for (int i = 0; i < cacheSet.size(); i++) {
                CacheLine cacheLine = cacheSet.get(i);
                if(cacheLine.getUsageCount() < lowerestUsageCacheId)
                    lowerestUsageCacheId = i;
                if(cacheLine.getTag().equals(tag)) {
                    data = transactionsOnCacheByProcessor(cacheLine, blockOffSetNumber, PROCESSOR_READ, tag, address);
                    return data;
                }
            }
            transactionsOnCacheByProcessor(cacheSet.get(lowerestUsageCacheId), blockOffSetNumber, PROCESSOR_READ, tag, address);
        }
        return data;
    }

    int transactionsOnCacheByProcessor(CacheLine cacheLine, int offset, int transaction, String tag, String address) {
        int result;
        if(protocol == MESI.PROTOCOL) {
            result = mesiProcessorTransactions(cacheLine, offset, transaction, tag, address);
        } else {
            result = msiProcessorTransactions(cacheLine, offset, transaction, tag, address);
        }
        return result;
    }

    int mesiProcessorTransactions(CacheLine cacheLine, int offset, int transaction, String tag, String address) {
        int result = 0;
        switch(cacheLine.getBlockState()) {
            case MESI.STATE_MODIFIED:
                if(transaction == PROCESSOR_WRITE) {
                    result = cacheLineWrite(cacheLine, offset, tag, address);
                } else if(transaction == PROCESSOR_READ) {
                    result = cacheLineRead(cacheLine,offset, tag, address);
                }
                break;
            case MESI.STATE_EXCLUSIVE:
                if(transaction == PROCESSOR_WRITE) {
                    result = cacheLineWrite(cacheLine,offset,tag, address);
                } else if(transaction == PROCESSOR_READ) {
                    result = cacheLineRead(cacheLine,offset, tag, address);
                }
                break;
            case MESI.STATE_SHARED:
                if(transaction == PROCESSOR_WRITE) {
                    result = cacheLineWrite(cacheLine,offset,tag, address);
                } else if(transaction == PROCESSOR_READ) {
                    result = cacheLineRead(cacheLine,offset, tag, address);
                }
                break;
            case MESI.STATE_INVALID:
                if(transaction == PROCESSOR_WRITE) {
                    result = cacheLineWrite(cacheLine,offset,tag, address);
                } else if(transaction == PROCESSOR_READ) { // check this again!!!!! ****** 99
                    result = cacheLineRead(cacheLine,offset, tag, address);
                }
                break;
            default:
                System.out.println("sth went wrong - mesi getdatafromothercaches .. state: " + cacheLine.getBlockState());
                break;
        }
        return result;
    }

    int msiProcessorTransactions(CacheLine cacheLine, int offset, int transaction, String tag, String address) {
        int result = 0;
        switch(cacheLine.getBlockState()) {
            case MSI.STATE_MODIFIED:
                if(transaction == PROCESSOR_WRITE) {
                    result = cacheLineWrite(cacheLine, offset, tag, address);
                } else if(transaction == PROCESSOR_READ) {
                    result = cacheLineRead(cacheLine,offset, tag, address);
                }
                break;
            case MSI.STATE_SHARED:
                if(transaction == PROCESSOR_WRITE) {
                    result = cacheLineWrite(cacheLine,offset,tag, address);
                } else if(transaction == PROCESSOR_READ) {
                    result = cacheLineRead(cacheLine,offset, tag, address);
                }
                break;
            case MSI.STATE_INVALID:
                if(transaction == PROCESSOR_WRITE) {
                    result = cacheLineWrite(cacheLine, offset, tag, address);
                } else if(transaction == PROCESSOR_READ) {
                    result = cacheLineRead(cacheLine,offset, tag, address);
                }
                break;
            default:
                System.out.println("sth went wrong - msi getdatafromothercaches .. state: " + cacheLine.getBlockState());
                break;
        }
        return result;
    }

    int cacheLineRead(CacheLine cacheLine, int offset, String tag, String address) {
        int result = 0;
        if(cacheLine.getTag().equals(tag) && cacheLine.getBlockState() != MSI.STATE_INVALID) { //hit, applies to both uni and mul proc
            System.out.println("hit");
            readHit++;
            result = cacheLine.getDataAtPosition(offset);
            cacheLine.incUsageCount();
        } else { //miss
            readMiss++;
            System.out.println("miss");
            if(isUniProcessor) { // will get from mem and replace
                memAccess++;
                cacheLine.updateCacheLine(tag, offset, MSI.STATE_MODIFIED);
                currWaitingOperation = new OperationPair(Bus.BUS_ACCESS_MEMORY_READ,cacheIdentity,address,cycles);
                Bus.insertTransactionOnBus(currWaitingOperation);
            } else {
                currWaitingOperation = new OperationPair(Bus.BUS_READ,cacheIdentity,address,cycles);
                Bus.insertTransactionOnBus(currWaitingOperation);
            }
            isWaiting = true;
        }
        return result;
    }

    void accessMemory() {
        incMemAccess();
    }

    int cacheLineWrite(CacheLine cacheLine, int offset, String tag, String address) {
        int result = 0;
        if(cacheLine.getTag().equals(tag) && cacheLine.getBlockState() != MSI.STATE_INVALID) {
            writeHit++;
            System.out.println("hit");
            cacheLine.setDataAtPosition(offset);
            cacheLine.setBlockState(MSI.STATE_MODIFIED);
            if(!isUniProcessor && cacheLine.getBlockState() == MSI.STATE_SHARED) {
                Bus.insertTransactionOnBus(new OperationPair(Bus.BUS_READEX,cacheIdentity,address,cycles));
            }
        } else { //miss : empty or sth is there
            writeMiss++;
            System.out.println("miss");
            if(isUniProcessor) {
                memAccess++;
                currWaitingOperation = new OperationPair(Bus.BUS_ACCESS_MEMORY_WRITE, cacheIdentity, address, cycles);
                Bus.insertTransactionOnBus(currWaitingOperation);
            } else {
                currWaitingOperation = new OperationPair(Bus.BUS_READEX,cacheIdentity,address,cycles);
                Bus.insertTransactionOnBus(currWaitingOperation);
            }
            isWaiting = true;
        }
        return result;
    }

    public void processorWriteCache(String address) {
        VisitAddress addr = new VisitAddress(address);
        Vector<CacheLine> cacheSet;
        if(associativity > 1)
            cacheSet = cacheLines.get(addr.set);
        else
            cacheSet = cacheLines.get(0);

        writeCache(cacheSet, addr.blockOffSetNumber, addr.set, addr.tag, address);
    }

    private void writeCache(Vector<CacheLine> cacheSet, int blockOffSetNumber , int modulo, String tag, String address) {
        if(associativity == 1) {
            CacheLine cacheLine = cacheSet.get(modulo);
            int data = transactionsOnCacheByProcessor(cacheLine, blockOffSetNumber, PROCESSOR_WRITE, tag, address);
        } else {
            int lowestUsed = 1000000;
            CacheLine cacheLine;
            for (int i=0; i<cacheSet.size(); i++) {
                cacheLine = cacheSet.get(i);
                if(cacheLine.getUsageCount()<lowestUsed)
                    lowestUsed = i;
                if(cacheLine.getTag().equals(tag)) {
                    int data = transactionsOnCacheByProcessor(cacheLine, blockOffSetNumber, PROCESSOR_WRITE, tag, address);
                    return;
                }
            }
            cacheLine = cacheSet.get(lowestUsed);
            transactionsOnCacheByProcessor(cacheLine, blockOffSetNumber, PROCESSOR_WRITE, tag, address);
        }
    }

    public int executeBusRead(OperationPair op) {
        VisitAddress address = new VisitAddress(op.getAddress());
        Vector<CacheLine> cacheSet;
        if(associativity > 1)
            cacheSet = cacheLines.get(address.set);
        else
            cacheSet = cacheLines.get(0);

        if(associativity == 1) { // fully associative
            CacheLine cacheLine = cacheSet.get(address.set);
            if(cacheLine.getTag().equals(address.tag))
                return transactionsOnCacheByBus(cacheLine, BUS_READ);
        } else {
            for (int i = 0; i < cacheSet.size(); i++) {
                CacheLine cacheLine = cacheSet.get(i);
                if(cacheLine.getTag().equals(address.tag))
                    return transactionsOnCacheByBus(cacheLine, BUS_READ);
            }
        }
        return 0;
    }

    int executeBusReadEx(OperationPair op) {
        int data = 0;
        VisitAddress addr = new VisitAddress(op.getAddress());
        Vector<CacheLine> cacheSet;
        if(associativity > 1)
            cacheSet = cacheLines.get(addr.set);
        else
            cacheSet = cacheLines.get(0);

        if(cacheSet.size() > associativity) {
            CacheLine cacheLine = cacheSet.get(addr.set);
            if(cacheLine.getTag().equals(addr.tag))
                return transactionsOnCacheByBus(cacheLine, BUS_READ_EXCLUSIVE);

        } else {
            for (CacheLine cacheLine : cacheSet) {
                if(cacheLine.getTag().equals(addr.tag))
                    return transactionsOnCacheByBus(cacheLine, BUS_READ_EXCLUSIVE);
            }
        }
        return data;
    }

    int transactionsOnCacheByBus(CacheLine cacheLine, int transaction) {
        int result;
        if(protocol == MESI.PROTOCOL) {
            result = mesiBusTransactions(cacheLine, transaction);
        } else {
            result = msiBusTransactions(cacheLine, transaction);
        }
        return result;
    }

    int mesiBusTransactions(CacheLine cacheLine, int transaction) {
        int result = 0;
        switch(cacheLine.getBlockState()) {
            case MESI.STATE_MODIFIED:
                if(transaction == BUS_READ)
                    cacheLine.setBlockState(MESI.STATE_SHARED);
                else if(transaction == BUS_READ_EXCLUSIVE)
                    cacheLine.setBlockState(MESI.STATE_INVALID);
                Bus.insertTransactionOnBus(new OperationPair(Bus.BUS_FLUSH,cacheIdentity,"dummy",cycles));
                cacheProvideDataSendOnBus();
                break;
            case MESI.STATE_EXCLUSIVE:
                if(transaction == BUS_READ)
                    cacheLine.setBlockState(MESI.STATE_SHARED);
                else if(transaction == BUS_READ_EXCLUSIVE)
                    cacheLine.setBlockState(MESI.STATE_INVALID);

                Bus.insertTransactionOnBus(new OperationPair(Bus.BUS_FLUSH,cacheIdentity,"dummy",cycles));
                cacheProvideDataSendOnBus();
                break;
            case MESI.STATE_SHARED:
                if(transaction == BUS_READ_EXCLUSIVE)
                    cacheLine.setBlockState(MESI.STATE_INVALID);
                cacheProvideDataSendOnBus();
                break;
            default:
                System.out.println("sth went wrong - mesi getdatafromothercaches .. state: " + cacheLine.getBlockState());
                break;
        }
        return result;
    }

    int msiBusTransactions(CacheLine cacheLine, int transaction) {
        int result = 0;
        switch(cacheLine.getBlockState()) {
            case MSI.STATE_MODIFIED:
                if(transaction == BUS_READ) {
                    cacheLine.setBlockState(MSI.STATE_SHARED);
                } else if(transaction == BUS_READ_EXCLUSIVE)
                    cacheLine.setBlockState(MSI.STATE_INVALID);
                Bus.insertTransactionOnBus(new OperationPair(Bus.BUS_FLUSH,cacheIdentity,"dummy",cycles));
                cacheProvideDataSendOnBus();
                break;
            case MSI.STATE_SHARED:
                if(transaction == BUS_READ_EXCLUSIVE)
                    cacheLine.setBlockState(MSI.STATE_INVALID);
                cacheProvideDataSendOnBus();
                break;
            case MSI.STATE_INVALID:
                break;
            default:
                System.out.println("sth went wrong - msi getdatafromothercaches ... state: "+cacheLine.getBlockState());
                break;
        }
        return result;
    }

    void cacheProvideDataSendOnBus() {
        Bus.busLine.setResult(true);
    }

    double logBase2(int num) {
        return Math.log(num) / Math.log(BINARY);
    }

    String addressBinaryString(String address) {
        return String.format("%32s",new BigInteger(address, 16).toString(BINARY)).replace(' ','0');
    }

    public void setClockCycles(int cycles) {
        this.cycles = cycles;
    }

    public void incMemAccess() {
        this.memAccess ++;
    }

    public void createlog() throws IOException{
        BufferedWriter bw = new BufferedWriter(new FileWriter(LOG_PATH+cacheIdentity+".txt", false));
        bw.write("Number of cycles:\t\t\t"+cycles);
        bw.newLine();
        bw.write("Number of memory access:\t\t"+memAccess);
        bw.newLine();
        bw.write("Number of read miss:\t\t\t"+readMiss);
        bw.newLine();
        bw.write("Number of read hit:\t\t\t"+readHit);
        bw.newLine();
        bw.write("Number of write miss:\t\t\t"+writeMiss);
        bw.newLine();
        bw.write("Number of write hit:\t\t\t"+writeHit);
        bw.newLine();
        bw.flush();
        bw.close();
    }

    public boolean isCacheWaitingForResult() {
        return isWaiting;
    }

    class VisitAddress {
        public String tag;
        public int blockOffSetNumber;
        public int set;

        public VisitAddress(String address) {
            String binaryString = addressBinaryString(address);
            tag = binaryString.substring(0,tagSize-1);
            String temp = binaryString.substring(tagSize, binaryString.length() - blockOffset - 1);
            int rowNumber = Integer.parseInt(temp, BINARY);
            blockOffSetNumber = Integer.parseInt(binaryString.substring(binaryString.length() - blockOffset), BINARY);
            set = rowNumber % blockSets;
        }
    }


    public static void main(String[] args) {
        Cache c = new Cache(1,64,4, MSI.PROTOCOL,1, true);
        System.out.println(String.format("%32s",new BigInteger("00123A", 16).toString(2)).replace(' ','0'));

    }
}


