package com.logicblox.steve.db;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.base.Function;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.logicblox.bloxweb.SimpleErrorCode;
import com.logicblox.bloxweb.service.ServiceException;
import com.logicblox.steve.common.Data;
import com.logicblox.steve.common.Status;

public class FakeDatabase implements Database {
  private Map<String, User> _users;
  private Map<String, Account> _accounts;
  private Map<String, Job> _jobFromId;
  private Map<String, Job> _jobFromClientId;
  private Table<String, String, JobImpl> _jobImpls;

  private JobState _jobState;

  public FakeDatabase(JobState jobState) {
    _users = new ConcurrentHashMap<String, User>();
    _accounts = new ConcurrentHashMap<String, Account>();

    _jobFromId = new ConcurrentHashMap<String, Job>();
    _jobFromClientId = new ConcurrentHashMap<String, Job>();

    _jobState = jobState;
    _jobImpls = HashBasedTable.create();

    addUser(
            new User(
                    "martin",
                    "logicblox.com",
                    "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAr5phZpWz3XxHL5qG5bJY\n" +
                            "PwIWBQuIhUCTL7VyPvjl3bEV1j2z2jQrAw62kdSSAQ4IP8bjwIaLlBQI7QE/Hn04\n" +
                            "hGSa73DuBJ0QOiGx7UsK77jSVTYkGZr3zqaA41aHtO6zVWj/0AOE3TfRgfg/wx7V\n" +
                            "RAzklEmpSGI/AUG23lN2PlbRx+GW4Or4GvdNibJxpfiiDIGdG/w+iJIGgPIwweDO\n" +
                            "Muq5NxWQRHLUONKmiWwNk9Fh86OIJjIyWZysX0lJMdFkFo1Ty08uZo7eQYE1ujW4\n" +
                            "M6PhDMPnQ91ahGWILUp02/Mc7x2yPNSiGvrltFn6iHJEbxokWXgItVn/ZZ0xNljq\n" +
                            "lQIDAQAB\n"));
    addUser(
            new User(
                    "rob",
                    "logicblox.com",
                    "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAvTq61D3Xp2D2LwKGcRY9\n" +
                            "c0nVcoSsz2Sg55thtvIOnLQ/K9YMYlgC7nGjZiTvJSdLstH+TzcRjwWM2OrSOjeL\n" +
                            "hZJ4w8+S2NoRVlj7iOpPtOkFh/HE+81Zmsgezry+GavE9n0GF3lJFmWo4SSuXvQA\n" +
                            "+98YXqhAJlimFqONqSruXfikT9CWqF9mn5asByAcnT0lJh7vCdPVyfuieFz3v1Ml\n" +
                            "8mZoCiTaVPCS3QZhzCJyThpTE9iBZdE+wOP5U7pMk9DY5p6F7xvP5tO/UCP9FC46\n" +
                            "6Ny22/NL/TUolbhqq53Lgaf8pf8McdvVoZ6Tv5JVUldnvKuwFKn7FaHBYO8k0qnv\n" +
                            "LwIDAQAB\n"));

    addAccount(new Account("logicblox.com"));

    // TODO bit of a hack
    setJobImpl("martin", "steve:internal:process-jobimpl", null, null);
  }

  private void addUser(User user) {
    _users.put(user.getId(), user);
  }

  private void addAccount(Account account) {
    _accounts.put(account.getId(), account);
  }

  public User getUser(String id) {
    return _users.get(id);
  }

  public Account getAccount(String id) {
    return _accounts.get(id);
  }

  @Override
  public synchronized ListenableFuture<String> createJob(
          String userId,
          String clientId,
          String implId,
          Collection<Data> inputs,
          String output,
          Map<String, String> metadata) {
    Job job;

    if (_jobFromClientId.containsKey(clientId)) {
      job = _jobFromClientId.get(clientId);
    } else {
      User user = getUser(userId);
      Account account = getAccount(user.getAccountId());

      JobImpl impl = getJobImpl(account, implId);
      if (impl == null) {
        return Futures.immediateFailedFuture(
                new ServiceException(
                        new SimpleErrorCode("NO_SUCH_JOB_IMPL", 400, "Job implementation '" + implId + "' does not exist")));
      }

      String id = UUID.randomUUID().toString();

      job = new Job(id, clientId, output, implId, metadata, inputs, impl.archive.getLocation());

      _jobState.initialize(id);

      _jobFromId.put(job.id, job);
      _jobFromClientId.put(job.clientId, job);
    }

    return Futures.immediateFuture(job.id);
  }

  @Override
  public synchronized ListenableFuture<String> addStatus(String jobId, final Status status) {
    ListenableFuture<Job> job = getJob(jobId);
    return Futures.transform(job, new Function<Job, String>() {
      public String apply(Job j) {
        j.addStatus(status);
        return j.id;
      }
    });
  }


  @Override
  public synchronized ListenableFuture<String> setResult(String jobId, final List<Data> output) {
    ListenableFuture<Job> job = getJob(jobId);
    return Futures.transform(job, new Function<Job, String>() {
      public String apply(Job j) {
        j.addOutputData(output);
        return j.id;
      }
    });
  }


  @Override
  public ListenableFuture<Job> getJob(String jobId) {
    Job job = _jobFromId.get(jobId);
    if (job == null) {
      return Futures.immediateFailedFuture(
              new ServiceException(
                      new SimpleErrorCode("NO_SUCH_JOB", 400, "Job '" + jobId + "' does not exist")));
    } else
      return Futures.immediateFuture(job);
  }

  @Override
  public synchronized ListenableFuture<JobImpl> getJobImpl(String userId, String implId) {
    User user = getUser(userId);
    Account account = getAccount(user.getAccountId());

    JobImpl impl = getJobImpl(account, implId);
    if (impl == null) {
      return Futures.immediateFailedFuture(
              new ServiceException(
                      new SimpleErrorCode("NO_SUCH_JOB_IMPL", 400, "Job implementation '" + implId + "' does not exist")));
    } else
      return Futures.immediateFuture(impl);
  }

  @Override
  public synchronized ListenableFuture<Iterable<JobImpl>> getJobImpl(String userId) {
    User user = getUser(userId);
    Account account = getAccount(user.getAccountId());

    Map<String, JobImpl> all = _jobImpls.row(account.getId());
    return Futures.immediateFuture((Iterable<JobImpl>) all.values());
  }

  public synchronized JobImpl getJobImpl(Account account, String implId) {
    return _jobImpls.get(account.getId(), implId);
  }

  @Override
  public synchronized ListenableFuture<String> setJobImpl(
          String userId,
          String implId,
          Data archive,
          Map<String, String> metadata) {
    User user = getUser(userId);
    Account account = getAccount(user.getAccountId());

    JobImpl impl = new JobImpl(implId, account.getId(), archive, metadata);

    _jobImpls.put(impl.account, impl.id, impl);

    return Futures.immediateFuture(implId);
  }
}
