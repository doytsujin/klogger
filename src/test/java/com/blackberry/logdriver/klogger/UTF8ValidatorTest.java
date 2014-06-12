package com.blackberry.logdriver.klogger;

import static org.junit.Assert.assertTrue;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class UTF8ValidatorTest {
  private static final String REPLACEMENT = "\uFFFD";
  private static final byte[] REPLACEMENT_BYTES = REPLACEMENT.getBytes(Charset
      .forName("UTF-8"));

  @Test
  public void testValidUTF8() throws Exception {
    UTF8Validator validator = new UTF8Validator();

    String[] tests = new String[] { "This is a short test",
        "This is much longer test that is also fine.  Wheee....    ",
        "Very Short", "" };

    for (String test : tests) {
      byte[] source = test.getBytes("UTF-8");

      validator.validate(source, 0, source.length);

      byte[] result = new byte[validator.getResultBuffer().limit()];
      validator.getResultBuffer().get(result);

      assertTrue(Arrays.equals(source, result));
    }
  }

  @Test
  public void testInvalidUTF8() throws UnsupportedEncodingException {
    // Each bad byte should be replaced by the replacement character
    List<Byte> incoming = new ArrayList<Byte>();
    List<Byte> expected = new ArrayList<Byte>();

    // This is a bad byte
    incoming.add((byte) 0xF5);
    for (byte b : REPLACEMENT_BYTES) {
      expected.add(b);
    }

    for (byte b : "some good characters ".getBytes("UTF-8")) {
      incoming.add(b);
      expected.add(b);
    }

    // Bad mulitbyte sequence
    // The first byte will be thrown away, but sinve the second byte is valid on
    // its own, it will be taken literally.
    incoming.add((byte) 0xE3);
    incoming.add((byte) 0x76);
    for (byte b : REPLACEMENT_BYTES) {
      expected.add(b);
    }
    expected.add((byte) 0x76);

    for (byte b : "more good stuff ".getBytes("UTF-8")) {
      incoming.add(b);
      expected.add(b);
    }

    byte[] in = new byte[incoming.size()];
    for (int i = 0; i < incoming.size(); i++) {
      in[i] = incoming.get(i);
    }

    byte[] exp = new byte[expected.size()];
    for (int i = 0; i < expected.size(); i++) {
      exp[i] = expected.get(i);
    }

    UTF8Validator validator = new UTF8Validator();
    validator.validate(in, 0, in.length);
    byte[] result = new byte[validator.getResultBuffer().limit()];
    validator.getResultBuffer().get(result);

    assertTrue(Arrays.equals(exp, result));
  }
}
