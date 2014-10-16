package com.logicblox.steve.db;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import com.logicblox.bloxweb.client.ClientConfigUtils;
import com.logicblox.bloxweb.client.DelimImportOptions;
import com.logicblox.bloxweb.client.DelimServiceClient;
import com.logicblox.bloxweb.client.ServiceConnector;
import com.logicblox.common.Option;
import com.logicblox.steve.common.Data;
import com.logicblox.steve.common.Status;
import com.logicblox.steve.common.Status.Event;
import com.logicblox.steve.tests.PrototypeTest;


/**
 * Tests for the LBDatabase implementation, which always goes to the database (no caching).
 */
public class LBDatabaseTest extends PrototypeTest {

  /**
   * The object under test.
   */
  final LBDatabase db = new LBDatabase();
  
  /**
   * A TDX client to load users for tests.
   */
  final DelimServiceClient usersClient = ServiceConnector.create()
      .setTransport(ClientConfigUtils.getTCPTransport())
      .setURI("http://localhost:8080/tdx/users")
      .createDelimClient();
   
      
  @Override
  protected String getPrototypeWorkspace() {
    return "lb-steve-frontend-test";
  }
  
  @Before
  public void loadUsers() throws Exception {
    usersClient.postDelimitedFile(
        new DelimImportOptions(
            ByteStreams.toByteArray(getClass().getResourceAsStream("users.csv")))).get();
  }
  
  @Test
  public void testGetUser() {
    final User martin = db.getUser("martin");
    Assert.assertEquals("logicblox.com", martin.getAccountId());
  }
  
  @Test
  public void testGetAccount() {
    Assert.assertEquals("logicblox.com", db.getAccount("martin").getId());
  }
  
  
  @Test
  public void testSetGetJobImpl() throws Exception {
    final String implId = db.setJobImpl(
        "martin", 
        "impl1", 
        new Data("/foo/impl1", "hash1"), 
        ImmutableMap.of("k1", "v1")).get();
    
    final JobImpl impl = db.getJobImpl("martin", implId).get();
    
    Assert.assertEquals(implId, impl.id);
    Assert.assertEquals("logicblox.com", impl.account);
    Assert.assertEquals("/foo/impl1", impl.archive.getLocation());
    Assert.assertEquals("hash1", impl.archive.getHash());
    Assert.assertEquals(ImmutableMap.of("k1", "v1"), impl.metadata);
  }
  
  @Test
  public void testSetGetMultipleJobImpl() throws Exception {
    db.setJobImpl(
        "martin", 
        "impl1", 
        new Data("/foo/impl1", "hash1"), 
        ImmutableMap.of("k1", "v1")).get();
    
    db.setJobImpl(
        "martin", 
        "impl2", 
        new Data("/foo/impl2", "hash2"), 
        ImmutableMap.of("k1", "v1")).get();
    
    // Rob has access to martin's impls
    final Iterable<JobImpl> impls = db.getJobImpl("rob").get();
    int count = 0;
    for(JobImpl impl: impls) {
      count++;
      if (impl.id.equals("impl1")) {
        Assert.assertEquals("logicblox.com", impl.account);
        Assert.assertEquals("/foo/impl1", impl.archive.getLocation());
        Assert.assertEquals("hash1", impl.archive.getHash());
        Assert.assertEquals(ImmutableMap.of("k1", "v1"), impl.metadata);
      } else if (impl.id.equals("impl2")) {
        Assert.assertEquals("logicblox.com", impl.account);
        Assert.assertEquals("/foo/impl2", impl.archive.getLocation());
        Assert.assertEquals("hash2", impl.archive.getHash());
        Assert.assertEquals(ImmutableMap.of("k1", "v1"), impl.metadata);
      } else {
        Assert.fail("Unrecognized Job Impl: " + impl);
      }
    }
    Assert.assertEquals("Expected 2 job implementations to be returned:", 2, count);
    
  }
  
  
  @Test
  public void testCreateGetJob() throws Exception {
    
    // add a job impl to refer to
    testSetGetJobImpl();
    
    final Collection<Data> inputs = ImmutableList.of(
        new Data("/foo/input1", Option.wrap("hash1")),
        new Data("/foo/input2")
    );
    
    final String jobId = db.createJob(
        "martin", 
        "1", 
        "impl1",
        inputs,
        "/out",
        ImmutableMap.of("k1", "v1")).get();
    
    final Job job = db.getJob(jobId).get();
    
    Assert.assertEquals(jobId, job.id);
    Assert.assertEquals("1", job.clientId);
    Assert.assertEquals("impl1", job.jobImplId);
    compareCollections(inputs, job.inputData);
    Assert.assertEquals("/out", job.outputPrefix);
    Assert.assertEquals(ImmutableMap.of("k1", "v1"), job.metadata);
    Assert.assertEquals("/foo/impl1", job.jobImplArchive);
  }
  
  

  @Test
  public void testAddStatus() throws Exception {
    
    // add a job impl to refer to
    testSetGetJobImpl();
    
    // create a job
    final Collection<Data> inputs = ImmutableList.of(
        new Data("/foo/input1", Option.wrap("hash1")),
        new Data("/foo/input2")
    );
    
    final String jobId = db.createJob(
        "martin", 
        "1", 
        "impl1",
        inputs,
        "/out",
        ImmutableMap.of("k1", "v1")).get();
    
    // now add status
    final Status status1 = new Status(12, Event.STARTED, "my machine", "great message");
    db.addStatus(jobId, status1).get();
    
    final Status status2 = new Status(32, Event.CANCELLED, "my machine", "great message");
    db.addStatus(jobId, status2).get();
    
    final Job job = db.getJob(jobId).get();
    
    Assert.assertEquals(jobId, job.id);
    Assert.assertEquals("1", job.clientId);
    Assert.assertEquals("impl1", job.jobImplId);
    compareCollections(inputs, job.inputData);
    Assert.assertEquals("/out", job.outputPrefix);
    Assert.assertEquals(ImmutableMap.of("k1", "v1"), job.metadata);
    Assert.assertEquals("/foo/impl1", job.jobImplArchive);
    Assert.assertEquals(ImmutableList.of(status1, status2), job.getStatus());
  }
  
  @Test
  public void testSetResult() throws Exception {
    
    // add a job impl to refer to
    testSetGetJobImpl();
    
    // create a job
    final Collection<Data> inputs = ImmutableList.of(
        new Data("/foo/input1", Option.wrap("hash1")),
        new Data("/foo/input2")
    );
    
    final String jobId = db.createJob(
        "martin", 
        "1", 
        "impl1",
        inputs,
        "/out",
        ImmutableMap.of("k1", "v1")).get();
    
    // now set the result
    final List<Data> output = ImmutableList.of(
        new Data("/result1", "hash1"), 
        new Data("/result2"));
    
    db.setResult(jobId, output).get();
        
    final Job job = db.getJob(jobId).get();
    
    Assert.assertEquals(jobId, job.id);
    Assert.assertEquals("1", job.clientId);
    Assert.assertEquals("impl1", job.jobImplId);
    compareCollections(inputs, job.inputData);
    Assert.assertEquals("/out", job.outputPrefix);
    Assert.assertEquals(ImmutableMap.of("k1", "v1"), job.metadata);
    Assert.assertEquals("/foo/impl1", job.jobImplArchive);    
    compareCollections(output, job.getOutputData());
  }
  
  
  
  
  /**
   * Compare unsorted collections.
   * 
   * @param expected
   * @param actual
   */
  public static <T> void compareCollections(Collection<T> expected, Collection<T> actual) {
    Assert.assertEquals("Collections differ in size:", expected.size(), actual.size());
    
    final Collection<T> missing = new HashSet<T>();
    for(T t : expected) {
      if (! actual.contains(t))
        missing.add(t);
    }
    
    if (! missing.isEmpty()) {
      Assert.fail("Missing elements: " + missing);
    }
  }
}

