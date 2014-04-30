package com.logicblox.steve.db;

import java.util.List;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.common.base.Function;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import com.logicblox.bloxweb.service.ServiceException;
import com.logicblox.bloxweb.SimpleErrorCode;

import com.logicblox.steve.common.Data;

public class FakeDatabase implements Database
{
  private Map<String, User> _users;
  private Map<String, Account> _accounts;
  private Map<String, Job> _jobFromId;
  private Map<String, Job> _jobFromClientId;

  private JobState _jobState;

  public FakeDatabase(JobState jobState)
  {
    _users = new HashMap<String, User>();
    _accounts = new HashMap<String, Account>();

    _jobFromId = new HashMap<String, Job>();
    _jobFromClientId = new HashMap<String, Job>();

    _jobState = jobState;

    addUser(
      new User(
        "martin", 
        "logicblox.com",
        "-----BEGIN PUBLIC KEY-----\n" +
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAr5phZpWz3XxHL5qG5bJY\n" +
        "PwIWBQuIhUCTL7VyPvjl3bEV1j2z2jQrAw62kdSSAQ4IP8bjwIaLlBQI7QE/Hn04\n" +
        "hGSa73DuBJ0QOiGx7UsK77jSVTYkGZr3zqaA41aHtO6zVWj/0AOE3TfRgfg/wx7V\n" +
        "RAzklEmpSGI/AUG23lN2PlbRx+GW4Or4GvdNibJxpfiiDIGdG/w+iJIGgPIwweDO\n" +
        "Muq5NxWQRHLUONKmiWwNk9Fh86OIJjIyWZysX0lJMdFkFo1Ty08uZo7eQYE1ujW4\n" +
        "M6PhDMPnQ91ahGWILUp02/Mc7x2yPNSiGvrltFn6iHJEbxokWXgItVn/ZZ0xNljq\n" +
        "lQIDAQAB\n" +
        "-----END PUBLIC KEY-----\n"));
    addUser(
      new User(
        "rob",
        "logicblox.com",
        "-----BEGIN PUBLIC KEY-----\n" +
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAvTq61D3Xp2D2LwKGcRY9\n" +
        "c0nVcoSsz2Sg55thtvIOnLQ/K9YMYlgC7nGjZiTvJSdLstH+TzcRjwWM2OrSOjeL\n" +
        "hZJ4w8+S2NoRVlj7iOpPtOkFh/HE+81Zmsgezry+GavE9n0GF3lJFmWo4SSuXvQA\n" +
        "+98YXqhAJlimFqONqSruXfikT9CWqF9mn5asByAcnT0lJh7vCdPVyfuieFz3v1Ml\n" +
        "8mZoCiTaVPCS3QZhzCJyThpTE9iBZdE+wOP5U7pMk9DY5p6F7xvP5tO/UCP9FC46\n" +
        "6Ny22/NL/TUolbhqq53Lgaf8pf8McdvVoZ6Tv5JVUldnvKuwFKn7FaHBYO8k0qnv\n" +
        "LwIDAQAB\n" +
        "-----END PUBLIC KEY-----\n"));
    
    addAccount(new Account("logicblox.com"));
  }

  private void addUser(User user)
  {
    _users.put(user.getId(), user);
  }

  private void addAccount(Account account)
  {
    _accounts.put(account.getId(), account);
  }

  public User getUser(String id)
  {
    return _users.get(id);
  }

  public Account getAccount(String id)
  {
    return _accounts.get(id);
  }

  @Override
  public synchronized ListenableFuture<Job> createJob(
    String userid, String clientId, String jobImpl, Collection<Data> inputs, String output)
  {
    Job job;

    if(_jobFromClientId.containsKey(clientId))
    {
      job = _jobFromClientId.get(clientId);
    }
    else
    {
      String id = UUID.randomUUID().toString();
      
      job = new Job();
      job.setId(id);
      job.setImpl(jobImpl);
      job.setClientId(clientId);
      job.setInputData(inputs);
      job.setOutputPrefix(output);

      _jobState.initialize(id);
      
      _jobFromId.put(job.getId(), job);
      _jobFromClientId.put(job.getClientId(), job);
    }
    
    return Futures.immediateFuture(job);
  }

  @Override
  public synchronized ListenableFuture<Job> getState(String jobId, boolean detail)
  {
    // an actual implementation would need to consider the detail
    // option. We do not.
    ListenableFuture<Job> job = getJob(jobId);
    return job;
  }

  @Override
  public synchronized ListenableFuture<Job> addStatus(String jobId, final Status status)
  {
    ListenableFuture<Job> job = getJob(jobId);
    return Futures.transform(job, new Function<Job, Job>()
    {
      public Job apply(Job j)
      {
        j.addStatus(status);
        return j;
      }
    });
  }

  @Override
  public synchronized ListenableFuture<Job> setResult(String jobId, final List<Data> output)
  {
    ListenableFuture<Job> job = getJob(jobId);
    return Futures.transform(job, new Function<Job, Job>()
    {
      public Job apply(Job j)
      {
        j.setOutputData(output);
        return j;
      }
    });
  }
    
  // TODO add user account and only return job when it exists in this account.
  // TODO throw authorization exception if the user is not allowed to access the job
  public synchronized ListenableFuture<Job> getResult(final String jobId)
  {
    ListenableFuture<Job> job = getJob(jobId);
    return Futures.transform(job, new Function<Job, Job>()
    {
      public Job apply(Job j)
      {
        if(!j.isSucceeded())
        {
          throw new ServiceException(
            new SimpleErrorCode("INVALID_STATE", 400, "Job '" + jobId + "' does not have SUCCEEDED state"));
        }

        return j;
      }
    });
  }

  private ListenableFuture<Job> getJob(String jobId)
  {
    Job job = _jobFromId.get(jobId);
    if(job == null)
    {
      return Futures.immediateFailedFuture(
        new ServiceException(
          new SimpleErrorCode("NO_SUCH_JOB", 400, "Job '" + jobId + "' does not exist")));
    }
    else
      return Futures.immediateFuture(job);
  }
}