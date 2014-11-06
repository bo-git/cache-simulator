package Object;

import Protocol.MESI;
import Protocol.MSI;

import java.math.BigInteger;
import java.util.Vector;

public class Cache {

    public static final int CPU_ADDRESS_SPACE = 32;
    public static final int BINARY = 2;
    public static final String BUS_READ = "busRead";
    public static final String BUS_READ_EXCLUSIVE = "busReadExclusive";
    public static final String PROCESSOR_READ = "processorRead";
    public static final String PROCESSOR_WRITE = "processorWrite";


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
    private boolean isCreateTransaction;
    CacheLine tempCacheLine; //for mesi read

    private Cache cache;

    public Cache getInstance(int cacheSize, int blockSize, int associativity, int protocol, int number) {
        if(cache == null) {
            cache = new Cache(cacheSize, blockSize, associativity, protocol, number);
        }
        return cache;
    }

    public Cache() {}

    public Cache(int cacheSize, int blockSize, int associativity, int protocol, int number) {
        this.cacheSize = cacheSize * 1024;
        this.blockSize = blockSize;
        this.associativity = associativity;
        this.protocol = protocol;
        this.cacheIdentity = number;
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
                "rowindex: "+rowIndex+"\tnum of sets: "+blockSets);
    }

    private void initCacheLines() {
        cacheLines = new Vector<Vector<CacheLine>>();

        if(associativity > 1){
            for (int j = 0; j < blockSets; j++) {
                cacheLines.add(new Vector<CacheLine>());
                for (int i = 0; i < associativity; i++) {
                    cacheLines.get(j).add(new CacheLine(blockSize,""));
                }
            }
        } else {
            cacheLines.add(new Vector<CacheLine>());
            for (int i = 0; i < numOfBlocks; i++) {
                cacheLines.get(0).add(new CacheLine(blockSize,""));
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

        int data = readCache(cacheSet, blockOffSetNumber, set, tagString);
        if(isCreateTransaction) {
            int dataFromOtherCaches = Bus.read(address, cacheIdentity);

            if(dataFromOtherCaches != 0 && protocol == MESI.PROTOCOL)
                tempCacheLine.setBlockState(MESI.STATE_SHARED);
            else if (dataFromOtherCaches == 0 && protocol == MESI.PROTOCOL)
                tempCacheLine.setBlockState(MESI.STATE_EXCLUSIVE);
        }
        return data;
    }

    void writeToCache(CacheLine cacheLine, int offset) {
        if(cacheLine.isDirtyBit()) {
            Memory.writeMemory(1); //update mem 99 ****
        }
        Memory.readMemory(1);
        cacheLine.setDataAtPosition(offset);
        if(protocol == MESI.PROTOCOL)
            cacheLine.setBlockState(MESI.STATE_SHARED);
        else
            cacheLine.setBlockState(MSI.STATE_SHARED);
        cacheLine.setDirtyBit();
    }

    int readFromCache(CacheLine cacheLine, int offset) {
        if(cacheLine.isDirtyBit()) {
            Memory.writeMemory(1); //update mem 99 ****
        }
        Memory.readMemory(1);
        cacheLine.setDataAtPosition(offset);
        if(protocol == MESI.PROTOCOL)
            cacheLine.setBlockState(MESI.STATE_SHARED);
        else
            cacheLine.setBlockState(MSI.STATE_SHARED);
        cacheLine.resetDirtyBit();
        return 1;
    }

    private int readCache(Vector<CacheLine> cacheSet, int blockOffSetNumber , int modulo, String tag) {
        int data = 0;
        if(cacheSet.size() > associativity) {
            CacheLine cacheLine = cacheSet.get(modulo);
            data = transactionsOnCacheByProcessor(cacheLine, blockOffSetNumber, PROCESSOR_READ, tag);
            if(data != 0) //hit
                return data;
        } else {
            for (int i = 0; i < cacheSet.size(); i++) {
                CacheLine cacheLine = cacheSet.get(i);
                data = transactionsOnCacheByProcessor(cacheLine, blockOffSetNumber, PROCESSOR_READ, tag);
                if(data != 0) //hit
                    return data;
            }
        }
        return data;
    }

    int transactionsOnCacheByProcessor(CacheLine cacheLine, int offset, String transaction, String tag) {
        int result;
        if(protocol == MESI.PROTOCOL) {
            result = mesiProcessorTransactions(cacheLine, offset, transaction, tag);
        } else {
            result = msiProcessorTransactions(cacheLine, offset, transaction, tag);
        }
        return result;
    }

    void processorWrite(CacheLine cacheLine, int offset, String tag) {
        if(cacheLine.getTag().equals(tag)) {
            cacheLine.setDataAtPosition(offset);
            cacheLine.setDirtyBit();
        } else {
            writeToCache(cacheLine,offset);
        }
    }

    int processorRead(CacheLine cacheLine, int offset, String tag) {
        int result;
        if(cacheLine.getTag().equals(tag)) {
            result = cacheLine.getDataAtPosition(offset);
        } else {
            result = readFromCache(cacheLine, offset);
        }
        return result;
    }

    int mesiProcessorTransactions(CacheLine cacheLine, int offset, String transaction, String tag) {
        int result = 0;
        switch(cacheLine.getBlockState()) {
            case MESI.STATE_MODIFIED:
                if(transaction.equals(PROCESSOR_WRITE)) {
                    processorWrite(cacheLine,offset,tag);
                    isCreateTransaction = false;
                } else if(transaction.equals(PROCESSOR_READ)) {
                    result = processorRead(cacheLine,offset, tag);
                    isCreateTransaction = false;
                }
                break;
            case MESI.STATE_EXCLUSIVE:
                if(transaction.equals(PROCESSOR_WRITE)) {
                    processorWrite(cacheLine,offset,tag);
                    cacheLine.setBlockState(MESI.STATE_MODIFIED);
                    isCreateTransaction = false;
                } else if(transaction.equals(PROCESSOR_READ)) {
                    result = processorRead(cacheLine,offset, tag);
                    isCreateTransaction = false;
                }
                break;
            case MESI.STATE_SHARED:
                if(transaction.equals(PROCESSOR_WRITE)) {
                    processorWrite(cacheLine,offset,tag);
                    cacheLine.setBlockState(MESI.STATE_MODIFIED);
                } else if(transaction.equals(PROCESSOR_READ)) {
                    result = processorRead(cacheLine,offset, tag);
                    isCreateTransaction = false;
                }
                break;
            case MESI.STATE_INVALID:
                if(transaction.equals(PROCESSOR_WRITE)) {
                    processorWrite(cacheLine,offset,tag);
                    cacheLine.setBlockState(MESI.STATE_MODIFIED);
                } else if(transaction.equals(PROCESSOR_READ)) { // check this again!!!!! ****** 99
                    tempCacheLine = cacheLine;
                    result = processorRead(cacheLine,offset, tag);
                }
                break;
            default:
                System.out.println("sth went wrong - mesi getdatafromothercaches .. state: " + cacheLine.getBlockState());
                break;
        }
        return result;
    }

    int msiProcessorTransactions(CacheLine cacheLine, int offset, String transaction, String tag) {
        int result = 0;
        switch(cacheLine.getBlockState()) {
            case MSI.STATE_MODIFIED:
                if(transaction.equals(PROCESSOR_WRITE)) {
                    //write
                    processorWrite(cacheLine,offset,tag);
                    isCreateTransaction = false;
                } else if(transaction.equals(PROCESSOR_READ)) {
                    result = processorRead(cacheLine,offset, tag);
                    isCreateTransaction = false;
                }
                break;
            case MSI.STATE_SHARED:
                if(transaction.equals(PROCESSOR_WRITE)) {
                    processorWrite(cacheLine,offset,tag);
                    cacheLine.setBlockState(MESI.STATE_MODIFIED);
                } else if(transaction.equals(PROCESSOR_READ)) {
                    result = processorRead(cacheLine,offset, tag);
                    isCreateTransaction = false;
                }
                break;
            case MSI.STATE_INVALID:
                if(transaction.equals(PROCESSOR_WRITE)) {
                    processorWrite(cacheLine,offset,tag);
                    cacheLine.setBlockState(MESI.STATE_MODIFIED);
                } else if(transaction.equals(PROCESSOR_READ)) { // check this again!!!!! ****** 99
                    result = processorRead(cacheLine,offset, tag);
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

        writeCache(cacheSet, blockOffSetNumber, set, tagString);
        if(isCreateTransaction)
            Bus.readEx(address, cacheIdentity);

    }

    private void writeCache(Vector<CacheLine> cacheSet, int blockOffSetNumber , int modulo, String tag) {
        if(cacheSet.size() > associativity) {
            CacheLine cacheLine = cacheSet.get(modulo);
            if(cacheLine.getTag().equals(tag)) {
                int data = transactionsOnCacheByProcessor(cacheLine, blockOffSetNumber, PROCESSOR_WRITE, tag);
            }
        } else {
            for (CacheLine cacheLine : cacheSet) {
                if(cacheLine.getTag().equals(tag)) {
                    int data = transactionsOnCacheByProcessor(cacheLine, blockOffSetNumber, PROCESSOR_WRITE, tag);
                }
            }
        }
    }

    public int busRead(String address) {
        int result  = requestReadCache(address);
        return result;
    }

    public void busReadEx(String address) {
        requestReadExCache(address);
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

        if(cacheSet.size() > associativity) {
            CacheLine cacheLine = cacheSet.get(set);

                int data = transactionsOnCacheByBus(cacheLine, blockOffSetNumber, BUS_READ, tagString);
                if(data != 0) //hit 99
                    return data;

        } else {
            for (int i = 0; i < cacheSet.size(); i++) {
                CacheLine cacheLine = cacheSet.get(i);
                if(cacheLine.getTag().equals(tagString)) {
                    int data = transactionsOnCacheByBus(cacheLine, blockOffSetNumber, BUS_READ, tagString);
                    if(data != 0) //hit 99
                        return data;
                }
            }
        }
        return 0;
    }

    void requestReadExCache(String address) {
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

                int data = transactionsOnCacheByBus(cacheLine, blockOffSetNumber, BUS_READ_EXCLUSIVE, tagString);

        } else {
            for (CacheLine cacheLine : cacheSet) {

                    int data = transactionsOnCacheByBus(cacheLine, blockOffSetNumber, BUS_READ_EXCLUSIVE, tagString);

            }
        }
    }

    int transactionsOnCacheByBus(CacheLine cacheLine, int offset, String transaction, String tag) {
        int result;
        if(protocol == MESI.PROTOCOL) {
            result = mesiBusTransactions(cacheLine, offset, transaction, tag);
        } else {
            result = msiBusTransactions(cacheLine, offset, transaction, tag);
        }
        return result;
    }

    int mesiBusTransactions(CacheLine cacheLine, int offset, String transaction, String tag) {
        int result = 0;
        switch(cacheLine.getBlockState()) {
            case MESI.STATE_MODIFIED:
                if(transaction.equals(BUS_READ)) {
                    if(cacheLine.getTag().equals(tag)) {
                        cacheLine.setBlockState(MSI.STATE_SHARED);
                        result = cacheLine.getDataAtPosition(offset);
                    }
                } else if(transaction.equals(BUS_READ_EXCLUSIVE)) {
                    if(cacheLine.getTag().equals(tag)) {
                        cacheLine.setBlockState(MSI.STATE_INVALID);
                        result = cacheLine.getDataAtPosition(offset);
                    }
                }
                break;
            case MESI.STATE_EXCLUSIVE:
                if(transaction.equals(BUS_READ)) {
                    if(cacheLine.getTag().equals(tag)) {
                        cacheLine.setBlockState(MSI.STATE_SHARED);
                        result = cacheLine.getDataAtPosition(offset);
                    }
                } else if(transaction.equals(BUS_READ_EXCLUSIVE)) {
                    if(cacheLine.getTag().equals(tag)) {
                        cacheLine.setBlockState(MSI.STATE_INVALID);
                        result = cacheLine.getDataAtPosition(offset);
                    }
                }
                break;
            case MESI.STATE_SHARED:
                if(transaction.equals(BUS_READ_EXCLUSIVE)) {
                    cacheLine.setBlockState(MSI.STATE_INVALID);
                }
//                result = cacheLine.getDataAtPosition(offset); // verify 99
                break;
            default:
                System.out.println("sth went wrong - mesi getdatafromothercaches .. state: " + cacheLine.getBlockState());
                break;
        }
        return result;
    }

    int msiBusTransactions(CacheLine cacheLine, int offset, String transaction, String tag) {
        int result = 0;
        switch(cacheLine.getBlockState()) {
            case MSI.STATE_MODIFIED:
                if(transaction.equals(BUS_READ)) {
                    if(cacheLine.getTag().equals(tag)) {
                        cacheLine.setBlockState(MSI.STATE_SHARED);
                        result = cacheLine.getDataAtPosition(offset);
                    }
                } else if(transaction.equals(BUS_READ_EXCLUSIVE)) {
                    if(cacheLine.getTag().equals(tag)) {
                        cacheLine.setBlockState(MSI.STATE_INVALID);
                        result = cacheLine.getDataAtPosition(offset);
                    }
                }
                break;
            case MSI.STATE_SHARED:
                if(transaction.equals(BUS_READ)) {
                    if(cacheLine.getTag().equals(tag)) {
//                        result = cacheLine.getDataAtPosition(offset); //99 verify
                    }
                } else if(transaction.equals(BUS_READ_EXCLUSIVE)) {
                    if(cacheLine.getTag().equals(tag)) {
                        cacheLine.setBlockState(MSI.STATE_INVALID);
//                        result = cacheLine.getDataAtPosition(offset);
                    }
                }
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
        return String.format("%0"+CPU_ADDRESS_SPACE+"d", new BigInteger(Integer.toBinaryString(Integer.parseInt(address,16))));
    }

    public static void main(String[] args) {
        Cache c = new Cache(8,64,4, MSI.PROTOCOL,1);
        c.processorReadCache("");


    }
}
