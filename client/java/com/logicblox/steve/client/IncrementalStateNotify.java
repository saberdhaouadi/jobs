package com.logicblox.steve.client;

import com.logicblox.steve.protocol.Frontend;

/**
 * Prints only state changes on notifications.
 */
public class IncrementalStateNotify implements SteveClientInterface.StateNotify {
  private int _prevCount = 0;
  private String _prevState = null;

  public synchronized void notify(Frontend.State state) {
    if (!state.getState().equals(_prevState))
      System.out.println("State: " + state.getState());

    for (int i = _prevCount; i < state.getStatusCount(); i++)
      Printers.print(state.getStatus(i));

    _prevState = state.getState();
    _prevCount = state.getStatusCount();
  }
}
