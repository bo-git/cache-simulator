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

    public CacheSimulation(String protocol, int numProcessor, int cacheSize, int associativity, int blkSize, String filename) {
        processors = new Vector<Processor>();
        boolean isUniProcessor = true;
        if(numProcessor > 1) isUniProcessor = false;
        for (int i = 0; i < numProcessor; i++) {
            System.out.println(i);
            if(protocol.equalsIgnoreCase(MESI_PROTOCOL))
                processors.add(new Processor(cacheSize, blkSize,associativity, MESI.PROTOCOL , i, isUniProcessor, filename));
            else
                processors.add(new Processor(cacheSize, blkSize,associativity, MSI.PROTOCOL , i, isUniProcessor, filename));
        }
        System.out.println(processors.size());
        operationQueue = new String[numProcessor];
        isComplete = new boolean[numProcessor];
        Bus.initBus(processors, filename);

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
        for (int i = 0; i < files.length; i++){
            System.out.println(files[i].getName());
            readers[i] = new BufferedReader(new FileReader(files[i]));
        }



        String line = null;
        int globalCycle = 1;
        int lineRead = 0;
        int k = 1;
        while(!isAllComplete()) {
            for(int j=0; j<readers.length; j++) {
                if(isComplete[j]) { // this is to continue supporting other processors which have not finish
                    processors.get(j).cacheSnoopBus(globalCycle);
                    continue;
                }

                if(processors.get(j).isCacheBlock()) {
//                    System.out.println(j+" waiting");
                    processors.get(j).cacheSnoopBus(globalCycle);
                    continue;
                }
                line = operationQueue[j];
                operationQueue[j] = null;
                if(line == null || line.isEmpty()) {
                    line = readers[j].readLine();
                    lineRead++;
                }

                if(line != null) {
                    String[] info = line.split(SPACE);
                    String anotherInst = null;
                    if(info[0].equals("0")) {
                        lineRead++;
                        anotherInst = readers[j].readLine();
                    }

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
            Bus.executeBusTransactions(globalCycle, lineRead);
            globalCycle++;

            if(lineRead > 10000*k) {
                System.out.println("hang in there!!!! line read = "+lineRead);
                k++;
            }
        }

    }

    void execute(String instruction, String address, int index, int currCycle) {
        switch (Integer.parseInt(instruction)) {
            case 0:
                processors.get(index).fetch();
                break;
            case 2:
//                System.out.println("\nrunning proc: "+index);
//                System.out.print("read @ addr: "+ address + "  ");
                processors.get(index).load(address, currCycle);
                break;
            case 3:
//                System.out.println("\nrunning proc: "+index);
//                System.out.print("write @ addr: "+ address+ "  ");
                processors.get(index).store(address, currCycle);
                break;
            default:
                System.out.println("something wrong");
                break;
        }
//        System.out.println();
    }

    void log(long time) throws IOException{
        for(Processor processor : processors) {
            processor.createLog();
        }
        Bus.log(time);
    }

    boolean isAllComplete() {
        for(boolean b : isComplete) {
            if(!b) return false;
        }
        System.out.println("done");
//        if(!Bus.isBusTransactionComplete || Bus.isAllOpsFinished() != 0)
//            return false;
        return true;
    }

    public static void main(String[] args) {
        try {
//            CacheSimulation cs = new CacheSimulation(args[0], Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]), Integer.parseInt(args[5]));
//            cs.run(args[1], args[2]);
            int[] processors = {1,2};
            int[] cacheSize = {1,8,32};
            int[] asso = {1,2,4};
            int[] blkSize = {8,64,128};
            String[] proto = {"msi","mesi"};
            String[] bm = {"fft","weather"};

            long beginOne = System.currentTimeMillis();
            CacheSimulation cs1 = new CacheSimulation("msi",2,1,4,32," msi 2 1 4 32 fft");
            cs1.run("fft",Integer.toString(2));
            long endOne = System.currentTimeMillis();
            long time = endOne - beginOne;
            cs1.log(time);



        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
