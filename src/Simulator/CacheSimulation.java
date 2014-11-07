package Simulator;

import java.util.Vector;
import Object.Processor;
import Object.Bus;
import Protocol.MESI;
import Protocol.MSI;

import java.io.*;

public class CacheSimulation {

    static final String MESI_PROTOCOL = "mesi";

    Vector<Processor> processors;

    public CacheSimulation() {}

    public CacheSimulation(String protocol, int numProcessor, int cacheSize, int associativity, int blkSize) {
        processors = new Vector<Processor>();
        for (int i = 0; i < numProcessor; i++) {
            if(protocol.equalsIgnoreCase(MESI_PROTOCOL))
                processors.add(new Processor(cacheSize, blkSize,associativity, MESI.PROTOCOL , i));
            else
                processors.add(new Processor(cacheSize, blkSize,associativity, MSI.PROTOCOL , i));
        }
        Bus.setNumOfProc(processors.size());
    }

    public void run(String inputFile, String numProcessor) throws Exception{
        String path = "Trace logs/"+inputFile.toLowerCase()+numProcessor;
        File dir = new File(path);

        if(!dir.isDirectory()) {
            System.out.println("no such folder");
            System.exit(0);
        }
        File[] files = dir.listFiles();
        BufferedReader[] readers = new BufferedReader[files.length];
        for (int i = 0; i < files.length; i++) {
            System.out.println(files[i].getName());
            readers[i] = new BufferedReader(new FileReader(files[i]));
            System.out.println("reader "+i+"  "+readers[i].toString());
        }
        String line;
        int counter = 0;
        while((line = readers[0].readLine()) != null) {
            execute(line, 0);
            System.out.println("reader 0 "+readers[0].toString());
            for (int j = 1; j < files.length; j++) {
                System.out.println("reader 1 "+readers[j].toString());
                line = readers[j].readLine();
                execute(line, j);
            }
            counter ++;
            if(counter == 15)
                return;
        }

    }

    void execute(String line, int index) {
        String[] info = line.split(" ");
        switch (Integer.parseInt(info[0])) {
            case 0:
                // fetch instruction
                System.out.println("fectching ... ");
                break;
            case 2:
//                System.out.println("reading "+info[1]);
                processors.get(index).read(info[1]);
                break;
            case 3:
                processors.get(index).write(info[1]);
//                System.out.println("writing "+info[1]);
                break;
            default:
                break;
        }
    }
    
    public static void main(String[] args) {
        try {
//            CacheSimulation cs = new CacheSimulation(args[0], Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]), Integer.parseInt(args[5]));
//            cs.run(args[1], args[2]);
            CacheSimulation cs1 = new CacheSimulation("msi",2,1,1,16);
            cs1.run("fft","2");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
