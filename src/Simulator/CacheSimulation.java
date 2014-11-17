package Simulator;

import java.util.Vector;
import Object.Processor;
import Object.Bus;
import Protocol.MESI;
import Protocol.MSI;

import java.io.*;

public class CacheSimulation {

    static final String MESI_PROTOCOL = "mesi";
    static final String SPACE = " ";
    static final String TRACE = "Trace logs/";

    Vector<Processor> processors;
    String[] operationQueue;
    boolean[] isComplete;

    public CacheSimulation() {}

    public CacheSimulation(String protocol, int numProcessor, int cacheSize, int associativity, int blkSize) {
        processors = new Vector<Processor>();
        boolean isUniProcessor = true;
        if(numProcessor > 1) isUniProcessor = false;
        for (int i = 0; i < numProcessor; i++) {
            System.out.println(i);
            if(protocol.equalsIgnoreCase(MESI_PROTOCOL))
                processors.add(new Processor(cacheSize, blkSize,associativity, MESI.PROTOCOL , i, isUniProcessor));
            else
                processors.add(new Processor(cacheSize, blkSize,associativity, MSI.PROTOCOL , i, isUniProcessor));
        }
        System.out.println(processors.size());
        operationQueue = new String[numProcessor];
        isComplete = new boolean[numProcessor];
        Bus.initBus(processors);

    }

    public void run(String inputFile, String numProcessor) throws Exception{
        String path = TRACE+inputFile.toLowerCase()+numProcessor;
        File dir = new File(path);
        if(!dir.isDirectory()) {
            System.out.println("no such folder");
            System.exit(0);
        }
        File[] files = dir.listFiles();
        BufferedReader[] readers = new BufferedReader[files.length];
        for (int i = 0; i < files.length; i++) {
            readers[i] = new BufferedReader(new FileReader(files[i]));
        }
        String line;
        int globalCycle = 1;
        while(!isAllComplete()) {
            for(int j=0; j<readers.length; j++) {
                if(processors.get(j).isCacheBlock()) {
                    processors.get(j).cacheSnoopBus(globalCycle);
                    continue;
                }
                line = operationQueue[j];
                if(line.isEmpty())
                    line = readers[j].readLine();
                if(line != null) {
                    String[] info = line.split(SPACE);
                    String anotherInst = null;
                    if(info[0].equals("0"))
                        anotherInst = readers[j].readLine();
                    execute(info[0],info[1],j, globalCycle); //the first inst
                    if(anotherInst != null && anotherInst.charAt(0) == '0')
                        operationQueue[j] = anotherInst;
                    else if(anotherInst != null) {
                        info = anotherInst.split(SPACE);
                        execute(info[0],info[1],j, globalCycle); //the second instru
                    }
                } else {
                    isComplete[j] = true;
                }
            }
            Bus.executeBusTransactions(globalCycle);
            globalCycle++;
        }
        log();
    }

    void execute(String instruction, String address, int index, int currCycle) {
        switch (Integer.parseInt(instruction)) {
            case 0:
                // fetch instruction
                processors.get(index).fetch();
                break;
            case 2:
                processors.get(index).load(address, currCycle);
                break;
            case 3:
                processors.get(index).store(address, currCycle);
                break;
            default:
                System.out.println("something wrong");
                break;
        }
    }

    void log() throws IOException{
        for(Processor processor : processors) {
            processor.createLog();
        }
    }

    boolean isAllComplete() {
        for(boolean b : isComplete) {
            if(!b) return false;
        }
        return true;
    }

    public static void main(String[] args) {
        try {
//            CacheSimulation cs = new CacheSimulation(args[0], Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]), Integer.parseInt(args[5]));
//            cs.run(args[1], args[2]);
            CacheSimulation cs1 = new CacheSimulation("msi",1,1,4,64);
            cs1.run("fft","1");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
