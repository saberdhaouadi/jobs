package com.logicblox.steve.tests;

import java.util.UUID;

import org.junit.After;
import org.junit.Before;

import com.logicblox.bloxweb.ConnectBloxExecutor;
import com.logicblox.bloxweb.ConnectBloxExecutors;
import com.logicblox.bloxweb.client.AdminClient;
import com.logicblox.common.Option;
import com.logicblox.connect.ConnectBlox;
import com.logicblox.connect.ConnectBlox.CloseNamedBranch;
import com.logicblox.connect.ConnectBlox.CreateNamedBranch;

/**
 * A support class for writing testcases based on a prototype workspace.
 * 
 * This is similar to lb-web's python PrototypeWorkspaceTestCase. This class creates a branch off
 * the prototype workspace during setUp and sets lb-web to host its services; then, on tearDown, it
 * closes the created branch.  During test execution, information about branch name is available
 * via protected fields.
 * 
 * TODO - this class if of general use and could be part of lb-common or lb-web.
 */
public abstract class PrototypeTest {

  /**
   * The name of the prototype workspace from which a branch is created. The default branch of this
   * workspace is branched during setUp. This value is only available while a test is executing. 
   */
  protected String prototype = null;
  
  /**
   * The name of the branch created for a test. This does not contain the workspace name, it only
   * contains the branch name itself. This value is only available while a test is executing. 
   */
  protected String branchId = null;
  
  /**
   * The full name of the branch created for a test. This contains both the prototype workspace name
   * and the branch part, and can be used to address the branch. This value is only available while
   * a test is executing. 
   */
  protected String branch = null;
  
  /**
   * @return the name of the workspace to be used as a prototype for test cases.
   */
  protected abstract String getPrototypeWorkspace();
    
  /**
   * Test suites may choose to define exactly the branch id. By default, a unique name is generated. 
   * 
   * @return an option that, if set, defines the id of the branch created for test cases.
   */
  protected Option<String> getBranchId() {
    return Option.<String>none();
  }
  
  /**
   * An executor to execute connectblox requests. This is always available to tests.
   */
  protected final ConnectBloxExecutor executor = 
      ConnectBloxExecutors.throwExceptions(
          ConnectBloxExecutors.execute());
  
  /**
   * The client used to make lb-web load and unload services.
   */
  protected final AdminClient client = new AdminClient();
  
  /**
   * On setUp, the default branch of the prototype workspace is branched, and the helper attributes
   * above are set. 
   * 
   * @throws Exception
   */
  @Before
  public void setUp() throws Exception {
    
    // Compute the branch name, either from a name specified by the subtype, or generating a
    // name based on the class name.
    branchId = 
       getBranchId().isSome() ? 
           getBranchId().unwrap() : 
           getClass().getSimpleName() + "_" + UUID.randomUUID();
   
    prototype = getPrototypeWorkspace();
    
    // Set the name of the workspace, which subtypes may refer to
    branch = prototype + "@" + branchId;
    
    // Create a request to branch the workspace
    final ConnectBlox.Request.Builder request = ConnectBlox.Request.newBuilder()
        .setCreateNamedBranch(CreateNamedBranch.newBuilder()
            .setWorkspace(prototype)
            .setBranch(branchId)
        ); 
    
    // Branch
    final ConnectBlox.Response response = executor.execute(request).get();
    if (response.hasException())
      throw new RuntimeException(response.getException().getMessage());
    
    // Load services from the branch 
    client.setSingleWorkspace(branch);
  }
  
  /**
   * On tearDown, the branch that was created is closed, and the helper attributes reset.
   * 
   * @throws Exception
   */
  @After
  public void tearDown() throws Exception {
    
    try {
      
      // Create a request to delete (close) the workspace branch
      final ConnectBlox.Request.Builder request = ConnectBlox.Request.newBuilder()
          .setCloseNamedBranch(CloseNamedBranch.newBuilder()
              .setWorkspace(prototype)
              .setBranch(branchId)
          ); 
      
      // Execute
      final ConnectBlox.Response response = executor.execute(request).get();
      if (response.hasException())
        throw new RuntimeException(response.getException().getMessage());
      
    } finally {
      // make sure we always reset the values
      branch = null;
      prototype = null;
      branchId = null; 
    }
  }
}
