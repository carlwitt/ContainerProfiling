package de.huberlin.wbi.containerprofiling;

import de.huberlin.wbi.cfjava.cuneiform.HaltMsg;
import de.huberlin.wbi.cfjava.cuneiform.RemoteWorkflow;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

/**
 * Usage: java -jar ContainerProfiling.jar <fromTask> <toTask>
 *     in any directory?
 */
public class Main {

    /** where to fetch the container statistics */
    private static final String cAdvisorHostAndPort = "localhost:8080";

    /** where the cuneiform erlang server runs */
    private static final String erlangServerHost = "localhost";
//    private static final String erlangServerHost = "0.0.0.0";
//    private static final String erlangServerHost = "127.0.0.1";
//    private static final String erlangServerHost = "192.168.127.11";

    public static void main(String[] args) throws InterruptedException, IOException {

        // define workflow
//        String workflowContent = readWorkflowFromFile(args[0]);
//        runWorkflow(workflowContent, Paths.get(args[0]+"-"+System.currentTimeMillis()));

        int from = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        int to = args.length > 1 ? Integer.parseInt(args[1]) : 181;

        for (int taskNumber = from; taskNumber <= to; taskNumber++) {
            profileSingleContainer(taskNumber);
        }


    }

    private static void profileSingleContainer(int taskNumber) throws IOException, InterruptedException {

        String containerName = "container-for-task-"+taskNumber;
        // in each task folder commands.txt contains the single command that corresponds to the task
        // it is executable within this directory using "bash commands.txt"
        // using bash -c, we first change into the dir and then execute the commands.txt
        String inContainerCommand = String.format("\"cd /tmp/cf-1/work/%s ; bash commands.txt\"", taskNumber);

        // use a throw-away container to execute the task's work and watch its resource profiles

        // collect metrics in a separate thread
        File metricsFile = new File("resource-usage-files","resource-usage-"+taskNumber+".json");
        metricsFile.createNewFile();
        CAdvisorMonitor metricsFetcherRunnable = new CAdvisorMonitor(cAdvisorHostAndPort, containerName, metricsFile);
        Thread metricsFetcherThread = new Thread(metricsFetcherRunnable);
        metricsFetcherThread.start();

        // use a throw-away container to execute the command
        String theCommand = "./exec-docker-container.sh "+taskNumber;
        System.out.println("exec " + theCommand);
        Process process = Runtime.getRuntime().exec(theCommand);
        process.waitFor();

        // stop the monitor
//        metricsFetcherRunnable.stopAndWriteOut();
        while(metricsFetcherThread.isAlive()){
            // wait until the monitoring thread gets a 500
        }
        System.out.println("finished task "+taskNumber);

    }

    static void startCAdvisor() throws IOException, InterruptedException {

        ProcessBuilder runCAdvisor = new ProcessBuilder("docker", "run",
                "--volume=/:/rootfs:ro",
                "--volume=/var/run:/var/run:rw",
                "--volume=/sys:/sys:ro",
                "--volume=/var/lib/docker/:/var/lib/docker:ro",
                "--publish=8080:8080",
                "--detach=true",
                "--name=cadvisor",
                "192.168.127.11:5000/google/cadvisor:0.25.0");

        runCAdvisor.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        runCAdvisor.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process p = runCAdvisor.start();
        p.waitFor();
        System.out.println("Started cuneiform cadvisor.");
    }


    static void startCuneiformServer() throws IOException, InterruptedException {

        // docker run -dti --name cf_server --publish 17489:17489 --restart=always 192.168.127.11:5000/witt/cf-server:0.1.0 erl -eval 'application:start(cf_lang).'
        // docker run -dt --name cf_server --publish 17489:17489 --restart=always 192.168.127.11:5000/witt/cf-server:0.1.0 erl -eval 'application:start(cf_lang).'
        // "docker","run","-dti","-p","17489:17489","--name","cf-server","192.168.127.11:5000/witt/cf-server:0.1.0
        ProcessBuilder runCfServer = new ProcessBuilder("docker","run",
                "-dti", // -ti to keep the container running
                "-p","17489:17489",
                "--rm",
                "--name","cf-server",
                "192.168.127.11:5000/witt/cf-server:0.1.0");

// does not work
//                new ProcessBuilder("docker", "run",
//                "-dti",
//                "--name", "cf_server",
//                "--publish", "17489:17489",
//                "--restart=always",
//                "192.168.127.11:5000/witt/cf-server:0.1.0",
//                "erl", "-eval", "'application:start(cf_lang).'");

        runCfServer.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        runCfServer.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process p = runCfServer.start();
        p.waitFor();
        Thread.sleep(1000);
        System.out.println("Started cuneiform erlang workflow server.");

    }

    static void runWorkflow(String workflowContent,Path buildDir) throws IOException, InterruptedException {
        RemoteWorkflow wf;

        JSONObject request;
        Queue<JSONObject> requests = new LinkedList<>();

        Charset utf8;
        StandardOpenOption create;
        HaltMsg haltMsg;

        utf8 = Charset.forName( "UTF-8" );
        create = StandardOpenOption.CREATE;

        // create new workflow instance
        wf = new RemoteWorkflow( workflowContent, erlangServerHost );

        // main loop
        while( true ) {

            Thread.sleep( 1000 );
            System.out.println( "new round ..." );

            // update workflow information
            wf.update();

            // checking if we're done
            if( !wf.isRunning() )
                break;

            // iterate over ready tasks
            while( wf.hasNextRequest() ) {

                // get next task
                request = wf.nextRequest();
                requests.add(request);
                System.out.println("Fetched request of type " + request.getJSONObject("data").getString("lam_name"));
            }

            while (!requests.isEmpty()){

                request=requests.poll();
                executeTaskEffi(wf, request, buildDir, utf8, create);

                wf.update();

            }
        }

        haltMsg = wf.getHaltMsg();
        System.out.println( "halted." );

        if( haltMsg.isOk() )
            System.out.println( "ok" );
        else
        if( haltMsg.isErrorWorkflow() )
            System.out.println( "eworkflow "+haltMsg.getLine()+" "+haltMsg.getModule()+" "+haltMsg.getReason() );
        else {
            System.out.println(String.format("Error executing task %s (%s)\n", haltMsg.getLamName(), haltMsg.getId().substring(0,8)) + haltMsg.getScript());
            System.out.println(haltMsg.getOutput());
        }
    }

    private static void executeTaskEffi(RemoteWorkflow wf, JSONObject request, Path buildDir, Charset utf8, StandardOpenOption create) throws IOException, InterruptedException {
        String requestFileName = "request.json";
        String summaryFileName = "summary.json";

        String id;
        Path requestFile;
        Path summaryFile;
        Path stdoutFile;
        Path stderrFile;
        ProcessBuilder processBuilder;
        Process process;
        int exitValue;
        String line;
        StringBuffer buf;
        JSONObject reply;

        // e.g., 8F5B2F17EAC35529CD225454BD0F096AB562BEE0FC600986A2B0CF414D6A5B96F02B12A6B5122DB0837DB83C9D78E01AC5CAE822F3B1648A81B63B43F3A38A22
        String lam_name = request.getJSONObject( "data" ).getString( "lam_name" );

        String dockerContainerName = lam_name + "-" + UUID.randomUUID().toString();

        // make a subdirectory for the task
        // TODO fix back
        Path sharedWorkDir = buildDir;
        Path containerWorkDir = buildDir.resolve( dockerContainerName );

        // specify the paths to the effi input and output files
        requestFile = containerWorkDir.resolve(requestFileName);
        summaryFile = containerWorkDir.resolve(summaryFileName);
        // specify the paths to console outputs
        stdoutFile = containerWorkDir.resolve( "stdout.txt" );
        stderrFile = containerWorkDir.resolve( "stderr.txt" );

        // remove all contents from the task's directory
        // TODO do delete
//        deleteIfExists( containerWorkDir );
        // TODO create
        Files.createDirectories( sharedWorkDir );
        Files.createDirectories( containerWorkDir );
        // write the request file, this is exactly what the server gives us.
        try( BufferedWriter writer = Files.newBufferedWriter( requestFile, utf8, create ) ) {
            writer.write( request.toString() );
        }

        // specify the effi run command...
        System.out.println("Starting docker container "+dockerContainerName);
        System.out.println("Input files: " + Arrays.toString(RemoteWorkflow.getInputSet(request).toArray()));
        String mountLocal = sharedWorkDir.toAbsolutePath() + ":/home/work";
        String requestFilePathContainer = "/home/work/" + dockerContainerName + "/" + requestFileName;
        String summaryFilePathContainer = "/home/work/" + dockerContainerName + "/" + summaryFileName;
        processBuilder = new ProcessBuilder("docker", "run",
                // mount working directory into /work in the container
                // this is where the effi files are exchanged between container and host OS
                "-v", mountLocal,
//                "--rm",  // remove container when finished
                "--name", dockerContainerName,
                // this gives root access, important for e.g, mounting a tmpfs
                "--privileged",
                "192.168.127.11:5000/witt/rnaseq-effi:1.1", // use the container with effi, variant calling binaries and data
                // TODO fix back to /work/?
                requestFilePathContainer,
                summaryFilePathContainer);
        processBuilder.directory( sharedWorkDir.toFile() );
        // ... and the sharedWorkDir where to capture its stdout and stderr
        processBuilder.redirectOutput( stdoutFile.toFile() );
        processBuilder.redirectError( stderrFile.toFile() );

        // collect metrics in a separate thread
        File metricsFile = new File(buildDir.resolve( dockerContainerName ).toString(), "resource-usage.json");
        metricsFile.createNewFile();
        CAdvisorMonitor metricsFetchRunnable = new CAdvisorMonitor(cAdvisorHostAndPort, dockerContainerName, metricsFile);
        Thread metricsFetchThread = new Thread(metricsFetchRunnable);
        metricsFetchThread.start();

        // wait for process to finish
        process = executeCommand(processBuilder,4);
        exitValue = process.waitFor();

        // stop the monitor
        metricsFetchRunnable.stopAndWriteOut();

        // in case of error, print stdout and stderr
        if( exitValue != 0 ) {

            try( BufferedReader reader = Files.newBufferedReader( stdoutFile, utf8 ) ) {
                while( ( line = reader.readLine() ) != null )
                    System.out.println( line );
            }
            try( BufferedReader reader = Files.newBufferedReader( stderrFile, utf8 ) ) {
                while( ( line = reader.readLine() ) != null )
                    System.out.println( line );
            }

            throw new RuntimeException( "Effi has shut down unsuccessfully." );
        }

        // if everything went fine, remove the container
        Process removeDockerContainer = new ProcessBuilder("docker", "rm", dockerContainerName)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .start();
        removeDockerContainer.waitFor();

        // read the EFFI summary file and send it back to the server
        try( BufferedReader reader = Files.newBufferedReader( summaryFile, utf8 ) ) {

            buf = new StringBuffer();
            while( ( line = reader.readLine() ) != null )
                buf.append( line ).append( '\n' );

            reply = new JSONObject( buf.toString() );
            wf.addReply( reply );
        }
    }

    private static Process executeCommand(ProcessBuilder processBuilder, int max_trials) throws InterruptedException, IOException {
        int trial;
        boolean suc;
        IOException ex;
        Process process;
        trial = 1;
        suc = false;
        ex = null;
        process = null;


        do {
            try {
                process = processBuilder.start();
                suc = true;
            }
            catch( IOException e ) {
                ex = e;
                Thread.sleep( 100 );
            }
        } while(!suc && trial++ <= max_trials );

        if( process == null ) {
            // ex cannot be null, because process is only null if the catch branch was taken
            throw ex;

        }
        return process;
    }


    private static void deleteIfExists( Path f ) throws IOException {

        if( !Files.exists( f, LinkOption.NOFOLLOW_LINKS ) )
            return;

        if( Files.isDirectory( f ) )
            try( DirectoryStream<Path> stream = Files.newDirectoryStream( f ) ) {
                for( Path p : stream )
                    deleteIfExists( p );
            }

        Files.delete( f );
    }

    static String readWorkflowFromFile(String location) throws IOException {
        // content = "deftask greet( out : person ) in bash *{ exit -1 }* greet( person: \"Marc\" );";
        String fname = location;
        StringBuffer buf = new StringBuffer();
        String line;
        try( BufferedReader r = Files.newBufferedReader( Paths.get( fname ) ) ) {
            while( ( line = r.readLine() ) != null )
                buf.append( line ).append( '\n' );
        }
        return buf.toString();
    }

    static final String memoryUseExampleWorkflow =
            "deftask useMemory( out : person )in bash *{\n" +
                    "  sleep 1s\n" +
                    "  whoami\n" +
                    "  mkdir /memreadtest\n" +
                    "  head -6 /proc/meminfo \n" +
                    "  mount -t tmpfs -o size=12000M tmpfs /memreadtest/\n" +
                    "  # fast generate 1GB of empty data into in-memory file system\n" +
                    "  dd if=/dev/zero of=/memreadtest/zero bs=1M count=1000\n" +
                    "  sleep 2s\n" +
                    "  dd if=/dev/zero of=/memreadtest/one bs=1M count=500\n" +
                    "  sleep 2s\n" +
                    "  rm /memreadtest/zero\n" +
                    "  sleep 2s\n" +
                    "  dd if=/dev/zero of=/memreadtest/two bs=1M count=800\n" +
                    "  sleep 1s\n" +
                    "  out=\"Hello $person\"\n" +
                    "}*\n" +
                    "\n" +
                    "greet0 = useMemory( person: \"Zar\" \"Peter\" );" +
                    "metaGreet = useMemory( person: greet0);" +
                    "metaGreet;";

}
