package com.logicblox.steve.frontend;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Date;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.text.SimpleDateFormat;

import com.googlecode.protobuf.format.JsonFormat;
import com.logicblox.sqs.SQSClientInterface;
import com.logicblox.sqs.SQSQueueHandle;
import com.logicblox.sqs.SQSReceivedMessage;
import com.logicblox.steve.common.Conversions;
import com.logicblox.steve.common.Data;
import com.logicblox.steve.common.Status;
import com.logicblox.steve.common.Status.StatusBuilder;
import com.logicblox.steve.db.Database;
import com.logicblox.steve.protocol.Backend;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

/**
 * Monitors a queue for status responses posted by workers about jobs, and then informs the updates
 * to a Database.
 */
public class StatusQueueClient {

  /**
   * Client to query the SQS queue.
   */
  private final SQSClientInterface _sqs;

  /**
   * Identification of queue to query.
   */
  private final SQSQueueHandle _queue;

  /**
   * Database to be informed of the status updates.
   */
  private final Database _db;

  /**
   * Request termination of the client.
   */
  private final AtomicBoolean _terminate = new AtomicBoolean(false);

  /**
   * Location to store processed status messages
   */
  private final String _dataDir;

  /**
   * Create a client for checking status responses using the sqs client, monitoring this queue, and
   * informing updates to this database.
   *
   * @param sqs
   * @param queue
   * @param db
   */
  public StatusQueueClient(SQSClientInterface sqs, SQSQueueHandle queue, Database db, String dataDir) {
    if (sqs == null)
      throw new IllegalArgumentException("queue client must be non-null");
    if (queue == null)
      throw new IllegalArgumentException("queue handle must be non-null");
    if (db == null)
      throw new IllegalArgumentException("db must be non-null");

    _sqs = sqs;
    _queue = queue;
    _db = db;
    _dataDir = dataDir;
  }

  /**
   * Start execution of the client.
   */
  public void start() {
    final Thread t = new Thread(new Runnable() {
      public void run() {
        loop();
      }
    });
    t.start();
  }

  /**
   * Request the client to stop (eventually).
   */
  public void stop() {
    _terminate.set(true);
  }

  /**
   * Main loop.
   */
  private void loop() {
    while (!_terminate.get()) {
      try {
        if (new File(_dataDir+"/../maintenance").exists()) {
          System.out.println("Maintenance in progress, sleeping for 30s...");
          Thread.sleep(30000);
          continue;
        }
        final List<SQSReceivedMessage> messages = _sqs.receive(_queue);

        int i = 0;
        for (final SQSReceivedMessage msg : messages) {
          try {
            String body = msg.getBody();
            processStatus(body);
            i++;
          } catch (Exception exc) {
            exc.printStackTrace();
          }
        }
        if(messages.size() != 0) { 
          System.out.println("Processed "+ i +" out of "+ messages.size() +" received status messages");
        }

        _sqs.delete(messages);

        // wait if there were no messages
        if (messages.size() == 0)
          Thread.sleep(500);

      } catch (Exception exc) {
        exc.printStackTrace();
      }
    }
  }

  private void writeStatusMessage(String jobid, String statusString) {
    try {
      Date date = new Date();
      //System.out.println(date);
      File dir = new File(_dataDir+"/"+new SimpleDateFormat("yyyyMMdd").format(date)+"/"+jobid);
      dir.mkdirs();
      //System.out.println(dir);
      FileUtils.writeStringToFile(new File(dir,new SimpleDateFormat("HHmmssSSS").format(date)), statusString);
    } catch (IOException e) {
      System.err.println("WARNING: Could not write status message to disk.");
      e.printStackTrace();
    }
  }

  /**
   * Process a single status message.
   *
   * @param statusString the message contents from the queue.
   */
  private void processStatus(String statusString) {

    // build the protobuf message from the contents
    final Backend.JobStatus.Builder builder = Backend.JobStatus.newBuilder();
    try {

      final JsonFormat format = new JsonFormat(JsonFormat.LOOSE);
      format.merge(new ByteArrayInputStream(statusString.getBytes()), builder);

    } catch (IOException exc) {
      // should not be possible
      throw new RuntimeException(exc);
    }

    final Backend.JobStatus protoStatus = builder.build();

    writeStatusMessage(protoStatus.getJob(), statusString);

    // start building a status object
    final StatusBuilder status = new StatusBuilder();
    status.machine = protoStatus.getMachine();
    status.timestamp = protoStatus.getTimestamp();

    switch (protoStatus.getStatusCode()) {

      case STARTED: {
        status.event = Status.Event.STARTED;
        break;
      }
      case PROGRESS: {
        status.event = Status.Event.PROGRESS;
        if (protoStatus.hasProgressDetails())
          status.message = protoStatus.getProgressDetails().getMessage();
        break;
      }
      case SUCCEEDED: {
        status.event = Status.Event.SUCCEEDED;
        if(protoStatus.hasResourceUsage()) {
          status.cpuUsage = protoStatus.getResourceUsage().getCpuUsage();
          status.maxMemory = protoStatus.getResourceUsage().getMaxMemory();
          status.maxDiskUsage = protoStatus.getResourceUsage().getMaxDiskUsage();
        }
        if (protoStatus.hasSucceededDetails()) {
          final List<Data> output =
                  Conversions.convertFileToData(protoStatus.getSucceededDetails().getOutputList());
          _db.setResult(protoStatus.getJob(), output);
        }
        break;
      }
      case FAILED: {
        status.event = Status.Event.FAILED;
        if (protoStatus.hasFailedDetails()) {
          final Backend.FailedDetails d = protoStatus.getFailedDetails();
          status.message =
                  (d.hasErrorCode() ? d.getErrorCode() + ": " : "") +
                          (d.hasErrorMessage() ? d.getErrorMessage() : "");
        }
        break;
      }
      default:
        System.err.println("error: status not yet supported: " + statusString);
    }

    // inform database of this new status message
    _db.addStatus(protoStatus.getJob(), status.build());
  }
}
