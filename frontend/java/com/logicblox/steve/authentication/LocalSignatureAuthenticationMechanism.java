package com.logicblox.steve.authentication;

import com.logicblox.bloxweb.authentication.SignatureAuthenticationMechanism;
import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import javax.xml.bind.DatatypeConverter;
import java.util.concurrent.ConcurrentHashMap;
import com.logicblox.bloxweb.client.SignUtils;
import java.io.StringReader;
import java.io.IOException;

public class LocalSignatureAuthenticationMechanism extends SignatureAuthenticationMechanism
{
  private ConcurrentHashMap<String, String> _keys;

  public LocalSignatureAuthenticationMechanism()
  {
    super();
    _keys = new ConcurrentHashMap<String, String>();
  }

  public void addKey(String user, String publicKey)
  {
    _keys.put(user,publicKey);
  }

  /**
   * TODO - comment. 
   * 
   * @param user
   * @param algorithm
   * @param signature
   * @param stringToVerify
   * @return
   */
  public boolean verify(String user, String algorithm, String signature, String stringToVerify)
  {
    String pubkey = _keys.get(user);

    if (pubkey == null)
      return false;

    if(!algorithm.equals("SHA512withRSA"))
      return false;

    try {
      pubkey = SignUtils.readPem(new StringReader(pubkey));
    } catch (IOException e) {
      _logger.warn("Error reading PEM from user "+user);
      return false;
    }

    byte[] pubkeyBytes = DatatypeConverter.parseBase64Binary(pubkey);
    X509EncodedKeySpec pubkeySpec = new X509EncodedKeySpec(pubkeyBytes);
    try
    {
      KeyFactory pubkeyFactory = KeyFactory.getInstance(algorithm.split("with")[1].split("and")[0]);
      Signature sig = Signature.getInstance(algorithm);
      sig.initVerify(pubkeyFactory.generatePublic(pubkeySpec));
      sig.update(stringToVerify.getBytes("UTF-8"));

      boolean verified = sig.verify(DatatypeConverter.parseBase64Binary(signature));

      if (!verified)
        _logger.warn("signature verification failed for user '" + user + "'");

      return verified;
    } catch (NoSuchAlgorithmException exc)
    {
      _logger.error("signature verification failed for user '" + user + "'", exc);
      return false;
    } catch (InvalidKeySpecException exc)
    {
      _logger.error("signature verification failed for user '" + user + "'", exc);
      return false;
    } catch (InvalidKeyException exc)
    {
      _logger.error("signature verification failed for user '" + user + "'", exc);
      return false;
    } catch (UnsupportedEncodingException exc)
    {
      _logger.error("signature verification failed for user '" + user + "'", exc);
      return false;
    } catch (SignatureException exc)
    {
      _logger.error("signature verification failed for user '" + user + "'", exc);
      return false;
    }
  }

}
