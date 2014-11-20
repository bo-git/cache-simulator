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
    private OperationPair currWaitingOperation, lastSnoopNCheckOp;
    String fileN;

    private int cycles, memAccess, readMiss, writeMiss, writeHit, readHit, executionCycle;

    private Cache cache;
    public Cache getInstance(int cacheSize, int blockSize, int associativity, int protocol, int number, boolean isUniProc, String filename) {
        if(cache == null) {
            cache = new Cache(cacheSize, blockSize, associativity, protocol, number, isUniProc, filename);
        }
        return cache;
    }
    public Cache() {}
    public Cache(int cacheSize, int blockSize, int associativity, int protocol, int number, boolean isUniProc, String filename) {
        this.cacheSize = cacheSize * 1024;
        this.blockSize = blockSize;
        this.associativity = associativity;
        this.protocol = protocol;
        this.cacheIdentity = number;
        this.isUniProcessor = isUniProc;
        fileN = filename;
        init();
    }

    private void init() {
        this.numOfBlocks = this.cacheSize / this.blockSize;
        this.blockSets = this.numOfBlocks / this.associativity; //fully associative =>> numOfBlocks = associativity
        initCacheLines();
        blockOffset = (int) logBase2(this.blockSize);
        rowIndex = (int) logBase2(this.blockSets);
        tagSize = CPU_ADDRESS_SPACE - blockOffset - rowIndex;
        lastSnoopNCheckOp = new OperationPair(-1,-1,"",-1);

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
//        System.out.print("Processor: "+cacheIdentity+"  snooping.... ");
        OperationPair op = Bus.busLine.getOp();
        if(op == null) return;
        if(Bus.busLine.getReceiverProcessorNumberOnBus() == cacheIdentity && Bus.isBusTransactionComplete) {
//            System.out.println("\nP"+cacheIdentity+" Receive info do update");
            if(op.getOpsNumber() == Bus.BUS_FLUSH) {
                Bus.busLine.setReceived();
                return;
            }
            if(Bus.busLine.getResultOnBus()) { //true if busRead, busReadEx reply && access mem
//                System.out.println("someone reply.. update");
                updateCacheBlockAfterSnooping(op);
                isWaiting = false;
                Bus.busLine.setReceived();
            } else {//no one has it, have to access mem no matter read or write
//                System.out.println("no reply continue the 7 cycles");
                memAccess++;
                //tell the bus continue execute 7 cycles
                if(protocol == MESI.PROTOCOL && op.getOpsNumber() == Bus.BUS_READ)
                    Bus.busLine.getOp().setPrevOpNumber(Bus.BUS_READ);

                Bus.continueBusReadorEx(cycle);
                isWaiting = true;
            }
//        } else if(!isUniProcessor && Bus.busLine.getReceiverProcessorNumberOnBus() != cacheIdentity) { //not equal to the same identity and not uniproc
        } else if(!isUniProcessor) { //not equal to the same identity and not uniproc
            if(lastSnoopNCheckOp.equals(op))
                return;
            if(op.getOpsNumber() == Bus.BUS_READ) executeBusRead(op);
            if(op.getOpsNumber() == Bus.BUS_READEX) executeBusReadEx(op);
            lastSnoopNCheckOp = op;
        }
    }

    void updateCacheBlockAfterSnooping(OperationPair op) {
        if(op.getOpsNumber() == Bus.BUS_FLUSH) {
            return; //don't have to care about flush
        }
        VisitAddress addr = new VisitAddress(op.getAddress());

        Vector<CacheLine> cacheSet;
        //System.out.println("associativity is "+associativity);
        if(associativity > 1)
            cacheSet = cacheLines.get(addr.set);
        else
            cacheSet = cacheLines.get(0);

        int state = 0;
        if(op.getOpsNumber() == Bus.BUS_READ) {
            state = MSI.STATE_SHARED;
            if(isUniProcessor)
                state = MSI.STATE_MODIFIED;
        } else if(op.getOpsNumber() == Bus.BUS_READEX)
            state = MSI.STATE_MODIFIED;

        if(op.getPrevOps() == Bus.BUS_READ && protocol == MESI.PROTOCOL) //handles special case for mesi
            state = MESI.STATE_EXCLUSIVE;

        //System.out.println("state : "+state);

        if(associativity == 1) { // 1-way set associative
            CacheLine cacheLine = cacheSet.get(addr.set);
            cacheLine.updateCacheLine(addr.tag,addr.blockOffSetNumber,state);
        } else {
            //System.out.println("checking ofr the correct block, putting tag: "+addr.tag);
            int beingAccessed = 0;
            int indexOfLeastUse =0;
            int origAge = 0;
            boolean isAddedToCache = false;
            CacheLine cacheLine;
            for (int i = 0; i < cacheSet.size(); i++) {
                cacheLine = cacheSet.get(i);
                if(cacheLine.getUsageCount() == cacheSet.size())
                    indexOfLeastUse = i;
                //System.out.print("cacheLine info: "+cacheLine.getTag()+"(tag)  count: "+cacheLine.getUsageCount());
                if(cacheLine.getTag().equals(addr.tag) || cacheLine.getTag().isEmpty()) {
                    beingAccessed = i;
                    isAddedToCache = true;
                    origAge = cacheLine.getUsageCount();
                    //System.out.println(" found position, update");
                    cacheLine.updateCacheLine(addr.tag, addr.blockOffSetNumber, state);
                    break;
                }
            }
            if(!isAddedToCache) {
                beingAccessed = indexOfLeastUse;
                cacheLine = cacheSet.get(beingAccessed);
                origAge = cacheLine.getUsageCount();
                cacheLine.updateCacheLine(addr.tag, addr.blockOffSetNumber, state);
            }
            reorderBlkBasedOnLRU(cacheSet, beingAccessed, origAge);
        }
    }

    public int processorReadCache(String address) {
        VisitAddress addr = new VisitAddress(address);
        executionCycle++;
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
            data = transactionsOnCacheByProcessor(cacheSet, modulo, blockOffSetNumber, PROCESSOR_READ, tag, address);
            if(data != 0) //hit
                return data;
        } else {
            for (int i = 0; i < cacheSet.size(); i++) {
                CacheLine cacheLine = cacheSet.get(i);
                if(cacheLine.getTag().equals(tag)) {
                    data = transactionsOnCacheByProcessor(cacheSet, i, blockOffSetNumber, PROCESSOR_READ, tag, address);
                    return data;
                }
            }
            //does not matter here as all blocks is a miss, so choose any block
            transactionsOnCacheByProcessor(cacheSet, 0, blockOffSetNumber, PROCESSOR_READ, tag, address);
        }
        return data;
    }

    int transactionsOnCacheByProcessor(Vector<CacheLine> cacheSets, int index, int offset, int transaction, String tag, String address) {
        int result =0;
        if(transaction == PROCESSOR_WRITE) {
            result = cacheLineWrite(cacheSets,index, offset, tag, address);
        } else if(transaction == PROCESSOR_READ) {
            result = cacheLineRead(cacheSets,index, offset, tag, address);
        }
        return result;
    }

//    int mesiProcessorTransactions(CacheLine cacheLine, int offset, int transaction, String tag, String address) {
//        int result = 0;
//        switch(cacheLine.getBlockState()) {
//            case MESI.STATE_MODIFIED:
//                if(transaction == PROCESSOR_WRITE) {
//                    result = cacheLineWrite(cacheLine, offset, tag, address);
//                } else if(transaction == PROCESSOR_READ) {
//                    result = cacheLineRead(cacheLine,offset, tag, address);
//                }
//                break;
//            case MESI.STATE_EXCLUSIVE:
//                if(transaction == PROCESSOR_WRITE) {
//                    result = cacheLineWrite(cacheLine,offset,tag, address);
//                } else if(transaction == PROCESSOR_READ) {
//                    result = cacheLineRead(cacheLine,offset, tag, address);
//                }
//                break;
//            case MESI.STATE_SHARED:
//                if(transaction == PROCESSOR_WRITE) {
//                    result = cacheLineWrite(cacheLine,offset,tag, address);
//                } else if(transaction == PROCESSOR_READ) {
//                    result = cacheLineRead(cacheLine,offset, tag, address);
//                }
//                break;
//            case MESI.STATE_INVALID:
//                if(transaction == PROCESSOR_WRITE) {
//                    result = cacheLineWrite(cacheLine,offset,tag, address);
//                } else if(transaction == PROCESSOR_READ) { // check this again!!!!! ****** 99
//                    result = cacheLineRead(cacheLine,offset, tag, address);
//                }
//                break;
//            default:
//                System.out.println("sth went wrong - mesi getdatafromothercaches .. state: " + cacheLine.getBlockState());
//                break;
//        }
//        return result;
//    }

//    int msiProcessorTransactions(Vector<CacheLine> cacheLine, int index, int offset, int transaction, String tag, String address) {
//        int result = 0;
//
//        switch(cacheLine.getBlockState()) {
//            case MSI.STATE_MODIFIED:
//                if(transaction == PROCESSOR_WRITE) {
//                    result = cacheLineWrite(cacheLine, offset, tag, address);
//                } else if(transaction == PROCESSOR_READ) {
//                    result = cacheLineRead(cacheLine,offset, tag, address);
//                }
//                break;
//            case MSI.STATE_SHARED:
//                if(transaction == PROCESSOR_WRITE) {
//                    result = cacheLineWrite(cacheLine,offset,tag, address);
//                } else if(transaction == PROCESSOR_READ) {
//                    result = cacheLineRead(cacheLine,offset, tag, address);
//                }
//                break;
//            case MSI.STATE_INVALID:
//                if(transaction == PROCESSOR_WRITE) {
//                    result = cacheLineWrite(cacheLine, offset, tag, address);
//                } else if(transaction == PROCESSOR_READ) {
//                    result = cacheLineRead(cacheLine,offset, tag, address);
//                }
//                break;
//            default:
//                System.out.println("sth went wrong - msi getdatafromothercaches .. state: " + cacheLine.getBlockState());
//                break;
//        }
//        return result;
//    }

    int cacheLineRead(Vector<CacheLine> cacheSet, int cacheLineIndex, int offset, String tag, String address) {
        int result = 0;
        CacheLine cacheLine = cacheSet.get(cacheLineIndex);
        //System.out.println("****** read: cache state: "+ cacheLine.getBlockState());
        if(cacheLine.getTag().equals(tag) && cacheLine.getBlockState() != MSI.STATE_INVALID) { //hit, applies to both uni and mul proc
            //System.out.println("hit");
            readHit++;
            cacheLine.getDataAtPosition(offset);
            int origAge = cacheLine.getUsageCount();
            cacheLine.setUsageCount();
            reorderBlkBasedOnLRU(cacheSet, cacheLineIndex, origAge);
            result = 1;
        } else { //miss
            readMiss++;
            //System.out.println("miss");
            if(isUniProcessor) memAccess++;
            currWaitingOperation = new OperationPair(Bus.BUS_READ,cacheIdentity,address,cycles);
            Bus.insertTransactionOnBus(currWaitingOperation);
            isWaiting = true;
        }
        return result;
    }

    int cacheLineWrite(Vector<CacheLine> cacheSet, int cacheLineIndex, int offset, String tag, String address) {
        int result = 0;
        CacheLine cacheLine = cacheSet.get(cacheLineIndex);
//        System.out.println("****** write: cache state: "+ cacheLine.getBlockState());
        if(cacheLine.getTag().equals(tag) && cacheLine.getBlockState() != MSI.STATE_INVALID) {
            writeHit++;
//            System.out.print("hit   ");
//            System.out.println("change state from "+cacheLine.getBlockState() +"  to modified");
            cacheLine.setBlockState(MSI.STATE_MODIFIED);
            if(!isUniProcessor && cacheLine.getBlockState() == MSI.STATE_SHARED) { //99 here is the optimizat => differen ops
                Bus.insertTransactionOnBus(new OperationPair(Bus.BUS_READEX,cacheIdentity,address,cycles));
                isWaiting = true;
                cacheLine.setBlockState(MSI.STATE_SHARED);
            }
            cacheLine.setDataAtPosition(offset);
            int origAge = cacheLine.getUsageCount();
            cacheLine.setUsageCount();
//            cacheLine.updateCacheLine(tag,offset,MSI.STATE_MODIFIED);
            reorderBlkBasedOnLRU(cacheSet, cacheLineIndex, origAge);
            result = 1;
        } else { //miss : empty or sth is there
            writeMiss++;
//            System.out.println("miss");
            if(isUniProcessor) memAccess++;
            currWaitingOperation = new OperationPair(Bus.BUS_READEX,cacheIdentity,address,cycles);
            Bus.insertTransactionOnBus(currWaitingOperation);
            isWaiting = true;
        }
        return result;
    }

    public void processorWriteCache(String address) {
        VisitAddress addr = new VisitAddress(address);
        executionCycle++;
        Vector<CacheLine> cacheSet;
        if(associativity > 1)
            cacheSet = cacheLines.get(addr.set);
        else
            cacheSet = cacheLines.get(0);

        writeCache(cacheSet, addr.blockOffSetNumber, addr.set, addr.tag, address);
    }

    private void writeCache(Vector<CacheLine> cacheSet, int blockOffSetNumber , int modulo, String tag, String address) {
        if(associativity == 1) {
            int data = transactionsOnCacheByProcessor(cacheSet, modulo, blockOffSetNumber, PROCESSOR_WRITE, tag, address);
        } else {
            CacheLine cacheLine;
            for (int i=0; i<cacheSet.size(); i++) {
                cacheLine = cacheSet.get(i);
                if(cacheLine.getTag().equals(tag)) {
                    int data = transactionsOnCacheByProcessor(cacheSet, i, blockOffSetNumber, PROCESSOR_WRITE, tag, address);
                    return;
                }
            }
            //at this point, no cache hit, will generate transaction, any block will do
            transactionsOnCacheByProcessor(cacheSet, 0, blockOffSetNumber, PROCESSOR_WRITE, tag, address);
        }
    }

    public int executeBusRead(OperationPair op) {
        VisitAddress address = new VisitAddress(op.getAddress());
        Vector<CacheLine> cacheSet;
        boolean isFound = false;
        if(associativity > 1)
            cacheSet = cacheLines.get(address.set);
        else
            cacheSet = cacheLines.get(0);

        if(associativity == 1) { // 1way associative
            CacheLine cacheLine = cacheSet.get(address.set);
            if(cacheLine.getTag().equals(address.tag))
                return transactionsOnCacheByBus(cacheLine, BUS_READ);
        } else {
            int chosenCache = -1;
            int origAge = -1;
            for (int i = 0; i < cacheSet.size(); i++) {
                CacheLine cacheLine = cacheSet.get(i);
                if(cacheLine.getTag().equals(address.tag)){
                    isFound = true;
                    chosenCache = i;
                    origAge = cacheLine.getUsageCount();
                    transactionsOnCacheByBus(cacheLine, BUS_READ);
                }
            }
            reorderBlkBasedOnLRU(cacheSet,chosenCache, origAge);
        }
//        System.out.println("reply to bus read : "+isFound);
        return 0;
    }

    int executeBusReadEx(OperationPair op) {
        int data = 0;
        VisitAddress addr = new VisitAddress(op.getAddress());
        Vector<CacheLine> cacheSet;
        boolean isFound = false;
        if(associativity > 1)
            cacheSet = cacheLines.get(addr.set);
        else
            cacheSet = cacheLines.get(0);

        if(associativity == 1) {
            CacheLine cacheLine = cacheSet.get(addr.set);
            if(cacheLine.getTag().equals(addr.tag))
                return transactionsOnCacheByBus(cacheLine, BUS_READ_EXCLUSIVE);

        } else {
            int chosenCache = -1;
            int origAge = 0;
            for (int i = 0; i < cacheSet.size(); i++) {
                CacheLine cacheLine = cacheSet.get(i);
                if(cacheLine.getTag().equals(addr.tag)){
                    isFound = true;
                    chosenCache = i;
                    origAge = cacheLine.getUsageCount();
                    transactionsOnCacheByBus(cacheLine, BUS_READ_EXCLUSIVE);
                }
            }
            reorderBlkBasedOnLRU(cacheSet,chosenCache, origAge);
        }
//        System.out.println("reply to busread ex : "+isFound);
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
                cacheLine.setUsageCount();
                //System.out.println("provide data!! from state "+MESI.STATE_EXCLUSIVE+" to "+cacheLine.getBlockState());
                cacheFlushDataOntoBus();
//                Bus.insertTransactionOnBus(new OperationPair(Bus.BUS_FLUSH,cacheIdentity,"dummy",cycles));
//                cacheProvideDataSendOnBus();
                break;
            case MESI.STATE_EXCLUSIVE:
                if(transaction == BUS_READ)
                    cacheLine.setBlockState(MESI.STATE_SHARED);
                else if(transaction == BUS_READ_EXCLUSIVE)
                    cacheLine.setBlockState(MESI.STATE_INVALID);
                //System.out.println("provide data!! from state "+MESI.STATE_EXCLUSIVE+" to "+cacheLine.getBlockState());
                cacheLine.setUsageCount();
//                Bus.insertTransactionOnBus(new OperationPair(Bus.BUS_FLUSH,cacheIdentity,"dummy",cycles));
                cacheProvideDataSendOnBus();
                break;
            case MESI.STATE_SHARED:
                if(transaction == BUS_READ_EXCLUSIVE)
                    cacheLine.setBlockState(MESI.STATE_INVALID);
                cacheLine.setUsageCount();
//                cacheProvideDataSendOnBus();
                break;
            default:
                //System.out.println("sth went wrong - mesi getdatafromothercaches .. state: " + cacheLine.getBlockState());
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
                //System.out.println("from modified change to "+cacheLine.getBlockState() + "  flushing");
                cacheLine.setUsageCount();
                cacheFlushDataOntoBus();
                break;
            case MSI.STATE_SHARED:
                if(transaction == BUS_READ_EXCLUSIVE)
                    cacheLine.setBlockState(MSI.STATE_INVALID);
                //System.out.println("from shared change to "+cacheLine.getBlockState());
                cacheLine.setUsageCount();
//                cacheProvideDataSendOnBus();
                break;
            case MSI.STATE_INVALID:
                break;
            default:
                //System.out.println("sth went wrong - msi getdatafromothercaches ... state: "+cacheLine.getBlockState());
                break;
        }
        return result;
    }

    void reorderBlkBasedOnLRU(Vector<CacheLine> set, int selectedBlock, int origAge) {
        if(selectedBlock == -1) return;
        for (int i = 0; i < set.size(); i++) {
            if(!set.get(i).getTag().isEmpty() && i != selectedBlock){
                if(set.get(i).getUsageCount() <= origAge || origAge == 0)
                    set.get(i).incUsageCount();
            }
        }
    }

    void cacheFlushDataOntoBus() {
        Bus.setHighPriorityOps(new OperationPair(Bus.BUS_FLUSH, cacheIdentity,"flushing",cycles));
        Bus.resetExpectedTerminationCycle(cycles, 1);
        Bus.busLine.setResult(true);
    }

    void cacheProvideDataSendOnBus() { //optimization
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
        BufferedWriter bw = new BufferedWriter(new FileWriter(LOG_PATH+cacheIdentity+" "+fileN+".txt", false));
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
        bw.write("Number of execution cycle:\t\t\t"+executionCycle);
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
//        Cache c = new Cache(1,32,2, MSI.PROTOCOL,1, true);
        System.out.println(12%32);
        System.out.println(String.format("%32s",new BigInteger("00123A", 16).toString(2)).replace(' ','0'));
    }
}


