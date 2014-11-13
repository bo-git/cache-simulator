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
    public static final String BUS_READ = "busRead";
    public static final String BUS_READ_EXCLUSIVE = "busReadExclusive";
    public static final String PROCESSOR_READ = "processorRead";
    public static final String PROCESSOR_WRITE = "processorWrite";
    public static final String LOG_PATH = "logs/Processor";

    private int cacheSize;
    private int numOfBlocks;
    private int blockSize;
    private int associativity;
    private int blockSets;
    private Vector<Vector<CacheLine>> cacheLines;

    int tagSize;        //the bound of tag, assumption the CPU has a 32 bit address size
    int blockOffset;    //the bound for offset to get the info in cache block
    int rowIndex;       //the row number (set number)

    private int protocol; //0 = msi , 1 = mesi
    private int cacheIdentity;
    private boolean isUniProcessor;
    private boolean isCreateTransaction;
    CacheLine tempCacheLine; //for mesi read

    private int cycles;
    private int memAccess;      //count
    private int readMiss;
    private int writeMiss;
    private int compulsoryMiss; //count
    private int capacityMiss;   //count
    private int conflictMiss;   //count

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

    public int processorReadCache(String address) {
        isCreateTransaction = true;
        String binaryString = addressBinaryString(address);
        String tagString = binaryString.substring(0,tagSize-1);
        int rowNumber = Integer.parseInt(binaryString.substring(tagSize, binaryString.length() - blockOffset - 1), BINARY);
        int blockOffSetNumber = Integer.parseInt(binaryString.substring(binaryString.length() - blockOffset), BINARY);

        int set = rowNumber % blockSets;
        Vector<CacheLine> cacheSet;
        if(associativity > 1)
            cacheSet = cacheLines.get(set);
        else
            cacheSet = cacheLines.get(0);

        int data = readCache(cacheSet, blockOffSetNumber, set, tagString, address);
        return data;
    }

    int writeToMemory(CacheLine cacheLine, int offset) {
        accessMemory();
        cacheLine.setDirtyBit();
        return 0;
    }

    void accessMemory() {
        incMemAccess();
        addCycles(10);
    }

    int readFromMemory(CacheLine cacheLine, int offset) {
        accessMemory(); //no matter dirty of now, will access mem
        cacheLine.resetDirtyBit();
        return 1;
    }

    private int readCache(Vector<CacheLine> cacheSet, int blockOffSetNumber , int modulo, String tag, String address) {
        int data = 0;
        if(cacheSet.size() > associativity) {
            CacheLine cacheLine = cacheSet.get(modulo);
            addCycles(1); //access cache
            data = transactionsOnCacheByProcessor(cacheLine, blockOffSetNumber, PROCESSOR_READ, tag, address);
            if(data != 0) //hit
                return data;
        } else {
            for (int i = 0; i < cacheSet.size(); i++) {
                addCycles(1); //access cache
                CacheLine cacheLine = cacheSet.get(i);
                data = transactionsOnCacheByProcessor(cacheLine, blockOffSetNumber, PROCESSOR_READ, tag, address);
                if(data != 0) //hit
                    return data;
            }
        }
        return data;
    }

    int transactionsOnCacheByProcessor(CacheLine cacheLine, int offset, String transaction, String tag, String address) {
        int result;
        if(protocol == MESI.PROTOCOL) {
            result = mesiProcessorTransactions(cacheLine, offset, transaction, tag, address);
        } else {
            result = msiProcessorTransactions(cacheLine, offset, transaction, tag, address);
        }
        return result;
    }

    int cacheLineWrite(CacheLine cacheLine, int offset, String tag, String address) {
        int result = 0;
        if(cacheLine.getTag().equals(tag) && (cacheLine.getBlockState() == MSI.STATE_MODIFIED || cacheLine.getBlockState() == MESI.STATE_EXCLUSIVE)) {
            cacheLine.setDataAtPosition(offset);
            cacheLine.setDirtyBit();
        } else if(cacheLine.getTag().equals(tag) && isUniProcessor) {
            cacheLine.setDataAtPosition(offset);
            cacheLine.setDirtyBit();
        } else { //miss : empty or sth is there
            writeMiss++;
            if(isUniProcessor)
                result = writeToMemory(cacheLine, offset);
            else {
                if(isCreateTransaction) {
                    addCycles(2);
                    Bus.readEx(address, cacheIdentity);
                    result = 1;
                }
            }
        }
        return result;
    }

    int cacheLineRead(CacheLine cacheLine, int offset, String tag, String address) {
        int result = 0;
        if(cacheLine.getTag().equals(tag) && cacheLine.getBlockState() != MSI.STATE_INVALID) { //hit
            result = cacheLine.getDataAtPosition(offset);
        } else if(cacheLine.getTag().equals(tag) && isUniProcessor)
            result = cacheLine.getDataAtPosition(offset);
        else { //miss
            readMiss++;
            if(isUniProcessor) {
                result = readFromMemory(cacheLine, offset);
            } else {
                if(isCreateTransaction) {
                    result = Bus.read(address, cacheIdentity);
                    addCycles(2); //wait:
                }
            }
        }
        return result;
    }

    int mesiProcessorTransactions(CacheLine cacheLine, int offset, String transaction, String tag, String address) {
        int result = 0;
        switch(cacheLine.getBlockState()) {
            case MESI.STATE_MODIFIED:
                if(transaction.equals(PROCESSOR_WRITE)) {
                    isCreateTransaction = false;
                    result = cacheLineWrite(cacheLine,offset,tag, address);
                    if(result != 0) result = writeToMemory(cacheLine, offset);
                } else if(transaction.equals(PROCESSOR_READ)) {
                    isCreateTransaction = false;
                    result = cacheLineRead(cacheLine,offset, tag, address);
                }
                break;
            case MESI.STATE_EXCLUSIVE:
                if(transaction.equals(PROCESSOR_WRITE)) {
                    isCreateTransaction = false;
                    result = cacheLineWrite(cacheLine,offset,tag, address);
                    if(result != 0) result = writeToMemory(cacheLine, offset);
                    cacheLine.setBlockState(MESI.STATE_MODIFIED);
                } else if(transaction.equals(PROCESSOR_READ)) {
                    isCreateTransaction = false;
                    result = cacheLineRead(cacheLine,offset, tag, address);
                }
                break;
            case MESI.STATE_SHARED:
                if(transaction.equals(PROCESSOR_WRITE)) {
                    result = cacheLineWrite(cacheLine,offset,tag, address);
                    if(result != 0) result = writeToMemory(cacheLine, offset);
                    cacheLine.setBlockState(MESI.STATE_MODIFIED);
                } else if(transaction.equals(PROCESSOR_READ)) {
                    isCreateTransaction = false;
                    result = cacheLineRead(cacheLine,offset, tag, address);
                }
                break;
            case MESI.STATE_INVALID:
                if(transaction.equals(PROCESSOR_WRITE)) {
                    result = cacheLineWrite(cacheLine,offset,tag, address);
                    if(result != 0) result = writeToMemory(cacheLine, offset);
                    cacheLine.setBlockState(MESI.STATE_MODIFIED);
                } else if(transaction.equals(PROCESSOR_READ)) { // check this again!!!!! ****** 99
//                    tempCacheLine = cacheLine;
                    result = cacheLineRead(cacheLine,offset, tag, address);
                    if(result != 0) { //someone has it
                        cacheLine.setBlockState(MESI.STATE_SHARED);
                    } else { //nothing
                        result = readFromMemory(cacheLine, offset);
                        cacheLine.setBlockState(MESI.STATE_EXCLUSIVE);
                    }
                    cacheLine.setDataAtPosition(offset);
                }
                break;
            default:
                System.out.println("sth went wrong - mesi getdatafromothercaches .. state: " + cacheLine.getBlockState());
                break;
        }
        return result;
    }

    int msiProcessorTransactions(CacheLine cacheLine, int offset, String transaction, String tag, String address) {
        int result = 0;
        switch(cacheLine.getBlockState()) {
            case MSI.STATE_MODIFIED:
                if(transaction.equals(PROCESSOR_WRITE)) {
                    isCreateTransaction = false;
                    result = cacheLineWrite(cacheLine,offset,tag, address);
                } else if(transaction.equals(PROCESSOR_READ)) {
                    isCreateTransaction = false;
                    result = cacheLineRead(cacheLine,offset, tag, address);
                }
                break;
            case MSI.STATE_SHARED:
                if(transaction.equals(PROCESSOR_WRITE)) {
                    result = cacheLineWrite(cacheLine,offset,tag, address);
                    if(result != 0) result = writeToMemory(cacheLine, offset);
                    cacheLine.setBlockState(MESI.STATE_MODIFIED);
                } else if(transaction.equals(PROCESSOR_READ)) {
                    isCreateTransaction = false;
                    result = cacheLineRead(cacheLine,offset, tag, address);
                }
                break;
            case MSI.STATE_INVALID:
                if(transaction.equals(PROCESSOR_WRITE)) {
                    result = cacheLineWrite(cacheLine,offset,tag, address);
                    if(result != 0) result = writeToMemory(cacheLine, offset);
                    cacheLine.setBlockState(MESI.STATE_MODIFIED);
                } else if(transaction.equals(PROCESSOR_READ)) { // check this again!!!!! ****** 99
                    result = cacheLineRead(cacheLine,offset, tag, address);
                    if(result == 0) { //nothing
                        result = readFromMemory(cacheLine, offset);
                    }
                    cacheLine.setDataAtPosition(offset);
                    cacheLine.setBlockState(MSI.STATE_SHARED);
                }
                break;
            default:
                System.out.println("sth went wrong - msi getdatafromothercaches .. state: " + cacheLine.getBlockState());
                break;
        }
        return result;
    }

    public void processorWriteCache(String address) {
        isCreateTransaction = true;
        String binaryString = addressBinaryString(address);
        String tagString = binaryString.substring(0, tagSize-1);
        int rowNumber = Integer.parseInt(binaryString.substring(tagSize, binaryString.length() - blockOffset - 1), BINARY);
        int blockOffSetNumber = Integer.parseInt(binaryString.substring(binaryString.length() - blockOffset), BINARY);

        int set = rowNumber % cacheSize;
        Vector<CacheLine> cacheSet;
        if(associativity > 1)
            cacheSet = cacheLines.get(set);
        else
            cacheSet = cacheLines.get(0);

        writeCache(cacheSet, blockOffSetNumber, set, tagString, address);
    }

    private void writeCache(Vector<CacheLine> cacheSet, int blockOffSetNumber , int modulo, String tag, String address) {
        if(cacheSet.size() > associativity) {
            CacheLine cacheLine = cacheSet.get(modulo);
                int data = transactionsOnCacheByProcessor(cacheLine, blockOffSetNumber, PROCESSOR_WRITE, tag, address);

        } else {
            for (CacheLine cacheLine : cacheSet) {
                    int data = transactionsOnCacheByProcessor(cacheLine, blockOffSetNumber, PROCESSOR_WRITE, tag, address);

            }
        }
    }

    public int busRead(String address) {
        int result  = requestReadCache(address);
        return result;
    }

    public int busReadEx(String address) {
        return requestReadExCache(address);
    }

    int requestReadCache(String address) {
        String binaryString = addressBinaryString(address);
        String tagString = binaryString.substring(0, tagSize-1);
        int rowNumber = Integer.parseInt(binaryString.substring(tagSize, binaryString.length() - blockOffset - 1), BINARY);
        int blockOffSetNumber = Integer.parseInt(binaryString.substring(binaryString.length() - blockOffset), BINARY);

        int set = rowNumber % blockSets;
        Vector<CacheLine> cacheSet;
        if(associativity > 1)
            cacheSet = cacheLines.get(set);
        else
            cacheSet = cacheLines.get(0);

        if(cacheSet.size() > associativity) { // fully associative
            addCycles(1); //access cache
            CacheLine cacheLine = cacheSet.get(set);
            if(cacheLine.getTag().equals(tagString))
                return transactionsOnCacheByBus(cacheLine, blockOffSetNumber, BUS_READ);
        } else {
            for (int i = 0; i < cacheSet.size(); i++) {
                CacheLine cacheLine = cacheSet.get(i);
                addCycles(1); //access cache
                if(cacheLine.getTag().equals(tagString))
                    return transactionsOnCacheByBus(cacheLine, blockOffSetNumber, BUS_READ);
            }
        }
        return 0;
    }

    int requestReadExCache(String address) {
        int data = 0;
        String binaryString = addressBinaryString(address);
        String tagString = binaryString.substring(0, tagSize-1);
        int rowNumber = Integer.parseInt(binaryString.substring(tagSize, binaryString.length() - blockOffset - 1), BINARY);
        int blockOffSetNumber = Integer.parseInt(binaryString.substring(binaryString.length() - blockOffset), BINARY);

        int set = rowNumber % blockSets;
        Vector<CacheLine> cacheSet;
        if(associativity > 1)
            cacheSet = cacheLines.get(set);
        else
            cacheSet = cacheLines.get(0);

        if(cacheSet.size() > associativity) {
            CacheLine cacheLine = cacheSet.get(set);
            addCycles(1);
            if(cacheLine.getTag().equals(tagString))
                return transactionsOnCacheByBus(cacheLine, blockOffSetNumber, BUS_READ_EXCLUSIVE);

        } else {
            for (CacheLine cacheLine : cacheSet) {
                addCycles(1);
                if(cacheLine.getTag().equals(tagString))
                    return transactionsOnCacheByBus(cacheLine, blockOffSetNumber, BUS_READ_EXCLUSIVE);

            }
        }
        return data;
    }

    int transactionsOnCacheByBus(CacheLine cacheLine, int offset, String transaction) {
        int result;
        if(protocol == MESI.PROTOCOL) {
            result = mesiBusTransactions(cacheLine, offset, transaction);
        } else {
            result = msiBusTransactions(cacheLine, offset, transaction);
        }
        return result;
    }

    int mesiBusTransactions(CacheLine cacheLine, int offset, String transaction) {
        int result = 0;
        switch(cacheLine.getBlockState()) {
            case MESI.STATE_MODIFIED:
                if(transaction.equals(BUS_READ))
                    cacheLine.setBlockState(MSI.STATE_SHARED);
                else if(transaction.equals(BUS_READ_EXCLUSIVE))
                    cacheLine.setBlockState(MSI.STATE_INVALID);
                result = cacheLine.getDataAtPosition(offset);
                break;
            case MESI.STATE_EXCLUSIVE:
                if(transaction.equals(BUS_READ))
                    cacheLine.setBlockState(MSI.STATE_SHARED);
                else if(transaction.equals(BUS_READ_EXCLUSIVE))
                    cacheLine.setBlockState(MSI.STATE_INVALID);
                result = cacheLine.getDataAtPosition(offset);
                break;
            case MESI.STATE_SHARED:
                if(transaction.equals(BUS_READ_EXCLUSIVE))
                    cacheLine.setBlockState(MSI.STATE_INVALID);
//                result = cacheLine.getDataAtPosition(offset);
                break;
            default:
                System.out.println("sth went wrong - mesi getdatafromothercaches .. state: " + cacheLine.getBlockState());
                break;
        }
        return result;
    }

    int msiBusTransactions(CacheLine cacheLine, int offset, String transaction) {
        int result = 0;
        switch(cacheLine.getBlockState()) {
            case MSI.STATE_MODIFIED:
                if(transaction.equals(BUS_READ))
                    cacheLine.setBlockState(MSI.STATE_SHARED);
                else if(transaction.equals(BUS_READ_EXCLUSIVE))
                    cacheLine.setBlockState(MSI.STATE_INVALID);
                result = cacheLine.getDataAtPosition(offset);
                break;
            case MSI.STATE_SHARED:
                if(transaction.equals(BUS_READ_EXCLUSIVE))
                    cacheLine.setBlockState(MSI.STATE_INVALID);
//                result = cacheLine.getDataAtPosition(offset);
                break;
            default:
                System.out.println("sth went wrong - msi getdatafromothercaches ... state: "+cacheLine.getBlockState());
                break;
        }
        return result;
    }

    double logBase2(int num) {
        return Math.log(num) / Math.log(BINARY);
    }

    String addressBinaryString(String address) {
        return String.format("%32s",new BigInteger(address, 16).toString(BINARY)).replace(' ','0');
    }

    public void addCycles(int cycles) {
        this.cycles += cycles;
    }

    public void incMemAccess() {
        this.memAccess ++;
    }

    public void incCompulsoryMiss() {
        this.compulsoryMiss ++;
    }

    public void incCapacityMiss() {
        this.capacityMiss ++;
    }

    public void incConflictMiss() {
        this.conflictMiss ++;
    }

    public void createlog() throws IOException{
        BufferedWriter bw = new BufferedWriter(new FileWriter(LOG_PATH+cacheIdentity+".txt", false));
        bw.write("Number of cycles:\t\t\t"+cycles);
        bw.newLine();
        bw.write("Number of memory access:\t\t"+memAccess);
        bw.newLine();
        bw.write("Number of read miss:\t\t\t"+readMiss);
        bw.newLine();
        bw.write("Number of write miss:\t\t\t"+writeMiss);
        bw.newLine();
        bw.flush();
        bw.close();
    }

    public static void main(String[] args) {
        Cache c = new Cache(8,64,4, MSI.PROTOCOL,1, true);
//        c.addressBinaryString("d80d0200");
        System.out.println(String.format("%32s",new BigInteger("00123A", 16).toString(2)).replace(' ','0'));

    }
}
