/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.chukwa.datacollection.agent;

import org.apache.hadoop.chukwa.datacollection.DataFactory;
import org.apache.hadoop.chukwa.datacollection.adaptor.*;
import org.apache.hadoop.chukwa.datacollection.connector.*;
import org.apache.hadoop.chukwa.datacollection.connector.http.HttpConnector;
import org.apache.hadoop.chukwa.datacollection.test.ConsoleOutConnector;
import org.apache.hadoop.chukwa.util.PidFile;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.io.*;

/**
 * The local agent daemon that runs on each machine. This class is designed to
 * be embeddable, for use in testing.
 * 
 */
public class ChukwaAgent
{
  boolean DO_CHECKPOINT_RESTORE = true;
  //boolean WRITE_CHECKPOINTS = true;

  static Logger log = Logger.getLogger(ChukwaAgent.class);
  static ChukwaAgent agent = null;
  private static PidFile pFile = null;

  public static ChukwaAgent getAgent()
  {
    return agent;
  }

  Configuration conf = null;
  Connector connector = null;

  // doesn't need an equals(), comparator, etc
  private static class Offset
  {
    public Offset(long l, long id)
    {
      offset = l;
      this.id = id;
    }

    private volatile long id;
    private volatile long offset;
  }

  public static class AlreadyRunningException extends Exception
  {

    private static final long serialVersionUID = 1L;

    public AlreadyRunningException()
    {
      super("Agent already running; aborting");
    }
  }

  private final Map<Adaptor, Offset> adaptorPositions;

  // basically only used by the control socket thread.
  private final Map<Long, Adaptor> adaptorsByNumber;

  private File checkpointDir; // lock this object to indicate checkpoint in
  // progress
  private File initialAdaptors;
  private String CHECKPOINT_BASE_NAME; // base filename for checkpoint files
  private int CHECKPOINT_INTERVAL_MS; // min interval at which to write
  // checkpoints
  private static String tags = "";

  private Timer checkpointer;
  private volatile boolean needNewCheckpoint = false; // set to true if any
  // event has happened
  // that should cause a new checkpoint to be written

  private long lastAdaptorNumber = 0; // ID number of the last adaptor to be
  // started
  private int checkpointNumber; // id number of next checkpoint.
  // should be protected by grabbing lock on checkpointDir

  private final AgentControlSocketListener controlSock;

  /**
   * @param args
   * @throws AdaptorException
   */
  public static void main(String[] args) throws AdaptorException
  {

    pFile = new PidFile("Agent");
    Runtime.getRuntime().addShutdownHook(pFile);

    try
    {
      if (args.length > 0 && args[0].equals("-help")) {
        System.out.println("usage:  LocalAgent [-noCheckPoint]" +
            "[default collector URL]");
        System.exit(0);
      }
      ChukwaAgent localAgent = new ChukwaAgent();

      if (agent.anotherAgentIsRunning())
      {
        System.out
            .println("another agent is running (or port has been usurped). " +
            		"Bailing out now");
        System.exit(-1);
      }

      int uriArgNumber = 0;
      if (args.length > 0)
      {
        if (args[0].equalsIgnoreCase("-noCheckPoint"))
        {
          agent.DO_CHECKPOINT_RESTORE = false;
          uriArgNumber = 1;
        }
        if (args[uriArgNumber].equals("local"))
          agent.connector = new ConsoleOutConnector(agent);
        else
        {
          if (!args[uriArgNumber].contains("://"))
            args[uriArgNumber] = "http://" + args[uriArgNumber];
          agent.connector = new HttpConnector(agent, args[uriArgNumber]);
        }
      } else
        agent.connector = new HttpConnector(agent);

      agent.connector.start();

      log.info("local agent started on port " + agent.getControlSock().portno);

    } catch (AlreadyRunningException e)
    {
      log
          .error("agent started already on this machine with same portno;" +
          		" bailing out");
      System.out
          .println("agent started already on this machine with same portno;" +
          		" bailing out");
      System.exit(0); // better safe than sorry
    } catch (Exception e)
    {
      e.printStackTrace();
    }
  }

  private boolean anotherAgentIsRunning()
  {
    return !controlSock.isBound();
  }

  /**
   * @return the number of running adaptors inside this local agent
   */
  public int adaptorCount()
  {
    return adaptorsByNumber.size();
  }

  public ChukwaAgent() throws AlreadyRunningException
  {
    ChukwaAgent.agent = this;

    readConfig();

    // almost always just reading this; so use a ConcurrentHM.
    // since we wrapped the offset, it's not a structural mod.
    adaptorPositions = new ConcurrentHashMap<Adaptor, Offset>();
    adaptorsByNumber = new HashMap<Long, Adaptor>();
    checkpointNumber = 0;
    try
    {
      if (DO_CHECKPOINT_RESTORE)
        restoreFromCheckpoint();
    } catch (IOException e)
    {
      log.warn("failed to restart from checkpoint: ", e);
    }

    try
    {
      if (initialAdaptors != null && initialAdaptors.exists())
        readAdaptorsFile(initialAdaptors);
    } catch (IOException e)
    {
      log.warn("couldn't read user-specified file "
          + initialAdaptors.getAbsolutePath());
    }

    controlSock = new AgentControlSocketListener(this);
    try
    {
      controlSock.tryToBind(); // do this synchronously; if it fails, we know
      // another agent is running.
      controlSock.start(); // this sets us up as a daemon
      log.info("control socket started on port " + controlSock.portno);

      if (CHECKPOINT_INTERVAL_MS > 0)
      {
        checkpointer = new Timer();
        checkpointer.schedule(new CheckpointTask(), 0, CHECKPOINT_INTERVAL_MS);
      }
    } catch (IOException e)
    {
      log.info("failed to bind to socket; aborting agent launch", e);
      throw new AlreadyRunningException();
    }

  }

  // FIXME: should handle bad lines here
  public long processCommand(String cmd)
  {
    String[] words = cmd.split(" ");
    if (words[0].equalsIgnoreCase("add"))
    {
      // words should contain (space delimited):
      // 0) command ("add")
      // 1) AdaptorClassname
      // 2) dataType (e.g. "hadoop_log")
      // 3) params <optional>
      // (e.g. for files, this is filename,
      // but can be arbitrarily many space
      // delimited agent specific params )
      // 4) offset

      long offset;
      try
      {
        offset = Long.parseLong(words[words.length - 1]);
      } catch (NumberFormatException e)
      {
        log.warn("malformed line " + cmd);
        return -1L;
      }
      String adaptorName = words[1];

      Adaptor adaptor = AdaptorFactory.createAdaptor(adaptorName);
      if (adaptor == null)
      {
        log.warn("Error creating adaptor from adaptor name " + adaptorName);
        return -1L;
      }

      String dataType = words[2];
      String streamName = "";
      String params = "";
      if (words.length > 4)
      { // no argument
        int begParams = adaptorName.length() + dataType.length() + 6;
        // length("ADD x type ") = length(x) + 5, i.e. count letters & spaces
        params = cmd.substring(begParams, cmd.length()
            - words[words.length - 1].length() - 1);
        streamName = params.substring(params.indexOf(" ") + 1, params.length());
      }
      long adaptorID;
      synchronized (adaptorsByNumber)
      {
        for (Map.Entry<Long, Adaptor> a : adaptorsByNumber.entrySet())
        {
          if (streamName.intern() == a.getValue().getStreamName().intern())
          {
            log.warn(params + " already exist, skipping.");
            return -1;
          }
        }
        adaptorID = ++lastAdaptorNumber;
        adaptorsByNumber.put(adaptorID, adaptor);
        adaptorPositions.put(adaptor, new Offset(offset, adaptorID));
        try
        {
          adaptor.start(adaptorID, dataType, params, offset, DataFactory
              .getInstance().getEventQueue());
          log.info("started a new adaptor, id = " + adaptorID);
          return adaptorID;

        } catch (Exception e)
        {
          log.warn("failed to start adaptor", e);
          // FIXME: don't we need to clean up the adaptor maps here?
        }
      }
    } else
      log.warn("only 'add' command supported in config files");

    return -1;
  }

  /**
   * Tries to restore from a checkpoint file in checkpointDir. There should
   * usually only be one checkpoint present -- two checkpoints present implies a
   * crash during writing the higher-numbered one. As a result, this method
   * chooses the lowest-numbered file present.
   * 
   * Lines in the checkpoint file are processed one at a time with
   * processCommand();
   * 
   * @return true if the restore succeeded
   * @throws IOException
   */
  public boolean restoreFromCheckpoint() throws IOException
  {
    synchronized (checkpointDir)
    {
      String[] checkpointNames = checkpointDir.list(new FilenameFilter()
      {
        public boolean accept(File dir, String name)
        {
          return name.startsWith(CHECKPOINT_BASE_NAME);
        }
      });
      
      if (checkpointNames == null) {
        log.error("Unable to list directories in checkpoint dir");
        return false;
      }
      if (checkpointNames.length == 0)
      {
        log.info("No checkpoints found in " + checkpointDir);
        return false;
      }

      if (checkpointNames.length > 2)
        log.warn("expected at most two checkpoint files in " + checkpointDir
            + "; saw " + checkpointNames.length);
      else if (checkpointNames.length == 0)
        return false;

      String lowestName = null;
      int lowestIndex = Integer.MAX_VALUE;
      for (String n : checkpointNames)
      {
        int index = Integer
            .parseInt(n.substring(CHECKPOINT_BASE_NAME.length()));
        if (index < lowestIndex)
        {
          lowestName = n;
          lowestIndex = index;
        }
      }

      checkpointNumber = lowestIndex;
      File checkpoint = new File(checkpointDir, lowestName);
      readAdaptorsFile(checkpoint);
    }
    return true;
  }

  private void readAdaptorsFile(File checkpoint) throws FileNotFoundException,
      IOException
  {
    BufferedReader br = new BufferedReader(new InputStreamReader(
        new FileInputStream(checkpoint)));
    String cmd = null;
    while ((cmd = br.readLine()) != null)
      processCommand(cmd);
    br.close();
  }

  /**
   * Called periodically to write checkpoints
   * 
   * @throws IOException
   */
  public void writeCheckpoint() throws IOException {
    needNewCheckpoint = false;
    synchronized (checkpointDir) {
      log.info("writing checkpoint " + checkpointNumber);

      FileOutputStream fos = new FileOutputStream(new File(checkpointDir,
          CHECKPOINT_BASE_NAME + checkpointNumber));
      PrintWriter out = new PrintWriter(new BufferedWriter(
          new OutputStreamWriter(fos)));

      for (Map.Entry<Adaptor, Offset> stat : adaptorPositions.entrySet()) {
        try {
          Adaptor a = stat.getKey();
          out.print("ADD " + a.getClass().getCanonicalName());
          out.println(" " + a.getCurrentStatus());
        } catch (AdaptorException e) {
          e.printStackTrace();
        }// don't try to recover from bad adaptor yet
      }

      out.close();
      File lastCheckpoint = new File(checkpointDir, CHECKPOINT_BASE_NAME
          + (checkpointNumber - 1));
      log.debug("hopefully removing old checkpoint file "
          + lastCheckpoint.getAbsolutePath());
      lastCheckpoint.delete();
      checkpointNumber++;
    }
  }

  public void reportCommit(Adaptor src, long uuid) {
    needNewCheckpoint = true;
    Offset o = adaptorPositions.get(src);
    if (o != null)
    {
      synchronized (o)
      { // order writes to offset, in case commits are processed out of order
        if (uuid > o.offset)
          o.offset = uuid;
      }

      log.info("got commit up to " + uuid + " on " + src + " = " + o.id);
    } else
    {
      log.warn("got commit up to " + uuid + "  for adaptor " + src
          + " that doesn't appear to be running: " + adaptorsByNumber.size()
          + " total");
    }
  }

  class CheckpointTask extends TimerTask {
    public void run()
    {
      try
      {
        if (needNewCheckpoint)
        {
          writeCheckpoint();
        }
      } catch (IOException e)
      {
        log.warn("failed to write checkpoint", e);
      }
    }
  }

  // for use only by control socket.
  public Map<Long, Adaptor> getAdaptorList() {
    return adaptorsByNumber;
  }

  /**
   * Stop the adaptor with given ID number. Takes a parameter to indicate
   * whether the adaptor should force out all remaining data, or just exit
   * abruptly.
   * 
   * If the adaptor is written correctly, its offset won't change after
   * returning from shutdown.
   * 
   * @param number
   *          the adaptor to stop
   * @param gracefully
   *          if true, shutdown, if false, hardStop
   * @return the number of bytes synched at stop. -1 on error
   */
  public long stopAdaptor(long number, boolean gracefully) {
    Adaptor toStop;
    long offset = -1;

    // at most one thread can get past this critical section with toStop != null
    // so if multiple callers try to stop the same adaptor, all but one will
    // fail
    synchronized (adaptorsByNumber) {
      toStop = adaptorsByNumber.remove(number);
    }
    if (toStop == null) {
      log.warn("trying to stop adaptor " + number + " that isn't running");
      return offset;
    }
    
    try {    	      
      if (gracefully) {
   	    offset = toStop.shutdown(); 
      }
    } catch (AdaptorException e) {
      log.error("adaptor failed to stop cleanly", e);
    } finally {
    	  needNewCheckpoint = true;
    }
    return offset;
  }

  public Configuration getConfiguration() {
    return conf;
  }

  public Connector getConnector() {
    return connector;
  }

  protected void readConfig() {
    conf = new Configuration();

    String chukwaHome = System.getenv("CHUKWA_HOME");
    if (chukwaHome == null) {
      chukwaHome = ".";
    }

    if (!chukwaHome.endsWith("/")) {
      chukwaHome = chukwaHome + File.separator;
    }
    log.info("Config - System.getenv(\"CHUKWA_HOME\"): [" + chukwaHome + "]");

    String chukwaConf = System.getProperty("CHUKWA_CONF_DIR");
    if (chukwaConf == null) {
      chukwaConf = chukwaHome + "conf" + File.separator;
    }
    if (!chukwaHome.endsWith("/")) {
      chukwaHome = chukwaHome + File.separator;
    }
    if (!chukwaConf.endsWith("/")) {
        chukwaConf = chukwaConf + File.separator;    	
    }
    log.info("Config - System.getenv(\"CHUKWA_HOME\"): [" + chukwaHome + "]");

    conf.addResource(new Path(chukwaConf + "chukwa-agent-conf.xml"));
    DO_CHECKPOINT_RESTORE = conf.getBoolean("chukwaAgent.checkpoint.enabled",
        true);
    CHECKPOINT_BASE_NAME = conf.get("chukwaAgent.checkpoint.name",
        "chukwa_checkpoint_");
    checkpointDir = new File(conf.get("chukwaAgent.checkpoint.dir", chukwaHome
        + "/var/"));
    CHECKPOINT_INTERVAL_MS = conf.getInt("chukwaAgent.checkpoint.interval",
        5000);
    if (!checkpointDir.exists())
    {
      checkpointDir.mkdirs();
    }
    tags = conf.get("chukwaAgent.tags", "cluster=\"unknown\"");

    log.info("Config - chukwaHome: [" + chukwaHome + "]");
    log.info("Config - CHECKPOINT_BASE_NAME: [" + CHECKPOINT_BASE_NAME + "]");
    log.info("Config - checkpointDir: [" + checkpointDir + "]");
    log.info("Config - CHECKPOINT_INTERVAL_MS: [" + CHECKPOINT_INTERVAL_MS
        + "]");
    log.info("Config - DO_CHECKPOINT_RESTORE: [" + DO_CHECKPOINT_RESTORE + "]");
    log.info("Config - tags: [" + tags + "]");

    if (DO_CHECKPOINT_RESTORE) {
      needNewCheckpoint = true;
      log.info("checkpoints are enabled, period is " + CHECKPOINT_INTERVAL_MS);
    }

    initialAdaptors = new File(chukwaConf + "initial_adaptors");
  }

  public void shutdown() {
    shutdown(false);
  }

  /**
   * Triggers agent shutdown. For now, this method doesn't shut down adaptors
   * explicitly. It probably should.
   */
  public void shutdown(boolean exit) {
    if (checkpointer != null)
      checkpointer.cancel();
    controlSock.shutdown(); // make sure we don't get new requests
    try {
      if (needNewCheckpoint)
        writeCheckpoint(); // write a last checkpoint here, before stopping
      // adaptors
    } catch (IOException e) {
    }

    synchronized (adaptorsByNumber) { 
      // shut down each adaptor
      for (Adaptor a : adaptorsByNumber.values()) {
        try {
          a.hardStop();
        } catch (AdaptorException e)
        {
          log.warn("failed to cleanly stop " + a, e);
        }
      }
    }
    if (exit)
      System.exit(0);
  }

  /**
   * Returns the last offset at which a given adaptor was checkpointed
   * 
   * @param a
   *          the adaptor in question
   * @return that adaptor's last-checkpointed offset
   */
  public long getOffset(Adaptor a) {
    return adaptorPositions.get(a).offset;
  }

  /**
   * Returns the control socket for this agent.
   */
  AgentControlSocketListener getControlSock() {
    return controlSock;
  }

  public static String getTags() {
    return tags;
  }

}
