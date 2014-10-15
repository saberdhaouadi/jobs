package com.logicblox.steve.tests;

import org.junit.internal.TextListener;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

import com.logicblox.steve.db.LBDatabaseTest;

@RunWith(Suite.class)
@Suite.SuiteClasses({
  LBDatabaseTest.class
})
public class Main
{
  public static void main(String[] ps)
  {
    JUnitCore core = new JUnitCore();
    core.addListener(new TextListener(System.out));
    Result result = core.run(Main.class);
    
    if(result.wasSuccessful())
    {
      System.exit(0);
    }
    else
    {
      System.exit(1);
    }
  }
}
