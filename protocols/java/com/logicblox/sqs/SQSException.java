package com.logicblox.sqs;

import java.util.Collection;

/**
 * Exception thrown by queue operations.
 */
public class SQSException extends Exception
{
  private static final long serialVersionUID = 1L;

  /**
   * If this exception represents a bunch of exceptions.
   */
  protected final Collection<? extends Throwable> _causes;
  
  /**
   * New queue exception with this message.
   *
   * @param msg
   */
  public SQSException(String msg)
  {
    super(msg);
    this._causes = null;
  }
  
  /**
   * New queue exception with this message and cause.
   *
   * @param msg
   * @param cause
   */
  public SQSException(String msg, Throwable cause)
  {
    super(msg, cause);
    this._causes = null;
  }
  
  public SQSException(String msg, Collection<? extends Throwable> causes)
  {
    super(msg);
    this._causes = causes;
  }
  
  @Override
  public String toString()
  {
    if (_causes == null)
      return super.toString();
    
    StringBuilder builder = new StringBuilder(this.getMessage());
    for (Throwable cause : _causes)
      builder.append("  ").append(cause.getMessage());
    
    return builder.toString();
  }
}
