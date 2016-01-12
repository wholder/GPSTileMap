/**
 * Created by wholder on 10/30/15.
 *
 * Encapsulates JSSC functionality into an easy to use class
 * See: https://code.google.com/p/java-simple-serial-connector/
 * And: https://github.com/scream3r/java-simple-serial-connector/releases
 */

  // Note: does not seem to receive characters when using a serial adapter based on a Prolific chip (or clone)

import java.io.File;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;

import jssc.*;

import javax.swing.*;

public class JSSCPort implements SerialPortEventListener {
  private static SerialNativeInterface serialInterface;
  private static final Pattern PORTNAMES_REGEXP;
  private static final String  PORTNAMES_PATH;
  private static final Map<String,Integer> baudRates = new LinkedHashMap<>();
  private static final int    eventMasks = SerialPort.MASK_RXCHAR;                // Also, SerialPort.MASK_CTS, SerialPort.MASK_DSR
  private static final int    flowCtrl = SerialPort.FLOWCONTROL_NONE;
  private static final int    defaultBaud = 115200;
  private ArrayBlockingQueue<Integer>  queue = new ArrayBlockingQueue<>(1000);
  private Preferences         prefs;
  private String              portName, prefId;
  private int                 baudRate, dataBits = 8, stopBits = 1, parity = 0;
  private SerialPort          serialPort;
  private RXEvent             rxHandler;
  private OpenClose           openCloseHandler;

  static {
    serialInterface = new SerialNativeInterface();
    switch (SerialNativeInterface.getOsType()) {
      case SerialNativeInterface.OS_LINUX: {
        PORTNAMES_REGEXP = Pattern.compile("(ttyS|ttyUSB|ttyACM|ttyAMA|rfcomm|ttyO)[0-9]{1,3}");
        PORTNAMES_PATH = "/dev/";
        break;
      }
      case SerialNativeInterface.OS_SOLARIS: {
        PORTNAMES_REGEXP = Pattern.compile("[0-9]*|[a-z]*");
        PORTNAMES_PATH = "/dev/term/";
        break;
      }
      case SerialNativeInterface.OS_MAC_OS_X: {
        PORTNAMES_REGEXP = Pattern.compile("(tty|cu)\\..*");
        PORTNAMES_PATH = "/dev/";
        break;
      }
      case SerialNativeInterface.OS_WINDOWS: {
        PORTNAMES_REGEXP = Pattern.compile("");
        PORTNAMES_PATH = "";
        break;
      }
      default: {
        PORTNAMES_REGEXP = null;
        PORTNAMES_PATH = null;
        break;
      }
    }
  }

  interface RXEvent {
    void rxChar (int cc);
  }

  interface OpenClose {
    void portOpened ();
    void portClosed();
  }

  static {
    baudRates.put("110",    SerialPort.BAUDRATE_110);
    baudRates.put("300",    SerialPort.BAUDRATE_300);
    baudRates.put("600",    SerialPort.BAUDRATE_600);
    baudRates.put("1200",   SerialPort.BAUDRATE_1200);
    baudRates.put("2400",   2400);    // Note: constant missing in JSSC 2.8.0-Release
    baudRates.put("4800",   SerialPort.BAUDRATE_4800);
    baudRates.put("9600",   SerialPort.BAUDRATE_9600);
    baudRates.put("14400",  SerialPort.BAUDRATE_14400);
    baudRates.put("19200",  SerialPort.BAUDRATE_19200);
    baudRates.put("38400",  SerialPort.BAUDRATE_38400);
    baudRates.put("57600",  SerialPort.BAUDRATE_57600);
    baudRates.put("115200", SerialPort.BAUDRATE_115200);
    baudRates.put("128000", SerialPort.BAUDRATE_128000);
    baudRates.put("256000", SerialPort.BAUDRATE_256000);
  }

  public JSSCPort (Preferences prefs, String prefId) {
    this.prefs = prefs;
    this.prefId = prefId;
    portName = prefs.get(prefId + ".serial.port", null);
    baudRate = prefs.getInt(prefId + ".serial.baud", defaultBaud);
  }

  public void setBaudRate (int rate) {
    prefs.putInt(prefId + ".serial.baud", baudRate = rate);
  }

  public void openPort () throws SerialPortException {
    for (String name : getPortNames()) {
      if (name.equals(portName)) {
        serialPort = new SerialPort(portName);
        serialPort.openPort();
        serialPort.setParams(baudRate, dataBits, stopBits, parity, false, false);  // baud, 8 bits, 1 stop bit, no parity
        serialPort.setEventsMask(eventMasks);
        serialPort.setFlowControlMode(flowCtrl);
        serialPort.addEventListener(this);
        if (openCloseHandler != null) {
          openCloseHandler.portOpened();
        }
      }
    }
  }

  public void closePort () throws SerialPortException {
    if (serialPort != null && serialPort.isOpened()) {
      serialPort.removeEventListener();
      serialPort.closePort();
      serialPort = null;
      if (openCloseHandler != null) {
        openCloseHandler.portClosed();
      }
    }
  }

  public String getPortName () {
    return portName;
  }

  // Implement SerialPortEventListener
  public void serialEvent (SerialPortEvent se) {
    try {
      if (se.getEventType() == SerialPortEvent.RXCHAR) {
        int rxCount = se.getEventValue();
        if (rxCount > 0) {
          byte[] inChars = serialPort.readBytes(rxCount);
          if (rxHandler != null) {
            for (byte cc : inChars) {
              rxHandler.rxChar((int) cc & 0xFF);
            }
          } else {
            for (byte cc : inChars) {
              if (queue.remainingCapacity() > 0) {
                queue.add((int) cc & 0xFF);
              }
            }
          }
        }
      }
    } catch (Exception ex) {
      ex.printStackTrace(System.out);
    }
  }

  public void setRXHandler (RXEvent handler) {
    if (rxHandler == null) {
      rxHandler = handler;
    }
  }

  public void removeRXHandler () {
    synchronized (this) {
      rxHandler = null;
    }
  }

  public void setOpenCloseHandler (OpenClose handler) {
    if (openCloseHandler == null) {
      openCloseHandler = handler;
    } else {
      throw new IllegalStateException("setOpenCloseHandler() handler already defined");
    }
  }

  public void removeOpenCloseHandler () {
    synchronized (this) {
      openCloseHandler = null;
    }
  }

  public void sendByte (byte data) throws SerialPortException {
    if (serialPort != null) {
      serialPort.writeByte(data);
    }
  }

  public void sendBytes (byte[] data) throws SerialPortException {
    if (serialPort != null) {
      serialPort.writeBytes(data);
    }
  }

  public void sendString (String data) throws SerialPortException {
    if (serialPort != null) {
      serialPort.writeString(data);
    }
  }

  public byte getChar () {
    int val = 0;
    if (rxHandler != null) {
      throw new IllegalStateException("Can't call when RXEvent is defined");
    } else {
      try {
        val = queue.take();
      } catch (InterruptedException ex) {
        ex.printStackTrace(System.out);
      }
    }
    return (byte) val;
  }

  public JMenu getPortMenu (String menuName) {
    JMenu menu = new JMenu(menuName);
    ButtonGroup group = new ButtonGroup();
    for (String pName : getPortNames()) {
      JRadioButtonMenuItem item = new JRadioButtonMenuItem(pName, pName.equals(portName));
      menu.setVisible(true);
      menu.add(item);
      group.add(item);
      item.addActionListener((ev) -> {
        portName = ev.getActionCommand();
        prefs.put(prefId + ".serial.port", portName);
      });
    }
    return menu;
  }

  public JMenu getBaudMenu (String menuName) {
    JMenu menu = new JMenu(menuName);
    ButtonGroup group = new ButtonGroup();
    for (String bRate : baudRates.keySet()) {
      int rate = baudRates.get(bRate);
      JRadioButtonMenuItem item = new JRadioButtonMenuItem(bRate, baudRate == rate);
      menu.add(item);
      menu.setVisible(true);
      group.add(item);
      item.addActionListener((ev) -> {
        String cmd = ev.getActionCommand();
        prefs.putInt(prefId + ".serial.baud", baudRate = Integer.parseInt(cmd));
        if (serialPort != null && serialPort.isOpened()) {
          try {
            serialPort.setParams(baudRate, dataBits, stopBits, parity, false, false);  // baud, 8 bits, 1 stop bit, no parity
          } catch (Exception ex) {
            ex.printStackTrace(System.out);
          }
        }
      });
    }
    return menu;
  }

  //since 2.1.0 -> Fully rewrited port name comparator
  private static final Comparator<String> PORTNAMES_COMPARATOR = new Comparator<String>() {
    @Override
    public int compare (String valueA, String valueB) {
      if (valueA.equalsIgnoreCase(valueB)) {
        return valueA.compareTo(valueB);
      }
      int minLength = Math.min(valueA.length(), valueB.length());
      int shiftA = 0;
      int shiftB = 0;
      for (int i = 0; i < minLength; i++) {
        char charA = valueA.charAt(i - shiftA);
        char charB = valueB.charAt(i - shiftB);
        if (charA != charB) {
          if (Character.isDigit(charA) && Character.isDigit(charB)) {
            int[] resultsA = getNumberAndLastIndex(valueA, i - shiftA);
            int[] resultsB = getNumberAndLastIndex(valueB, i - shiftB);
            if (resultsA[0] != resultsB[0]) {
              return resultsA[0] - resultsB[0];
            }
            if (valueA.length() < valueB.length()) {
              i = resultsA[1];
              shiftB = resultsA[1] - resultsB[1];
            } else {
              i = resultsB[1];
              shiftA = resultsB[1] - resultsA[1];
            }
          } else {
            if (Character.toLowerCase(charA) - Character.toLowerCase(charB) != 0) {
              return Character.toLowerCase(charA) - Character.toLowerCase(charB);
            }
          }
        }
      }
      return valueA.compareToIgnoreCase(valueB);
    }

    /**
     * Evaluate port <b>index/number</b> from <b>startIndex</b> to the number end. For example:
     * for port name <b>serial-123-FF</b> you should invoke this method with <b>startIndex = 7</b>
     *
     * @return If port <b>index/number</b> correctly evaluated it value will be returned<br>
     * <b>returnArray[0] = index/number</b><br>
     * <b>returnArray[1] = stopIndex</b><br>
     *
     * If incorrect:<br>
     * <b>returnArray[0] = -1</b><br>
     * <b>returnArray[1] = startIndex</b><br>
     *
     * For this name <b>serial-123-FF</b> result is:
     * <b>returnArray[0] = 123</b><br>
     * <b>returnArray[1] = 10</b><br>
     */
    private int[] getNumberAndLastIndex (String str, int startIndex) {
      String numberValue = "";
      int[] returnValues = {-1, startIndex};
      for (int i = startIndex; i < str.length(); i++) {
        returnValues[1] = i;
        char c = str.charAt(i);
        if (Character.isDigit(c)) {
          numberValue += c;
        } else {
          break;
        }
      }
      try {
        returnValues[0] = Integer.valueOf(numberValue);
      } catch (Exception ex) {
        //Do nothing
      }
      return returnValues;
    }
  };
  //<-since 2.1.0

  /**
   * Get sorted array of serial ports in the system using default settings:<br>
   * <p>
   * <b>Search path</b><br>
   * Windows - ""(always ignored)<br>
   * Linux - "/dev/"<br>
   * Solaris - "/dev/term/"<br>
   * MacOSX - "/dev/"<br>
   * <p>
   * <b>RegExp</b><br>
   * Windows - ""<br>
   * Linux - "(ttyS|ttyUSB|ttyACM|ttyAMA|rfcomm)[0-9]{1,3}"<br>
   * Solaris - "[0-9]*|[a-z]*"<br>
   * MacOSX - "tty.(serial|usbserial|usbmodem).*"<br>
   *
   * @return String array. If there is no ports in the system String[]
   * with <b>zero</b> length will be returned (since jSSC-0.8 in previous versions null will be returned)
   */
  public static String[] getPortNames () {
    if (PORTNAMES_PATH == null || PORTNAMES_REGEXP == null) {
      return new String[]{};
    }
    if (SerialNativeInterface.getOsType() == SerialNativeInterface.OS_WINDOWS) {
      return getWindowsPortNames(PORTNAMES_REGEXP, PORTNAMES_COMPARATOR);
    }
    return getUnixBasedPortNames(PORTNAMES_PATH, PORTNAMES_REGEXP, PORTNAMES_COMPARATOR);

  }

  /**
   * Get serial port names in Windows
   *
   * @since 2.3.0
   */
  private static String[] getWindowsPortNames (Pattern pattern, Comparator<String> comparator) {
    String[] portNames = serialInterface.getSerialPortNames();
    if (portNames == null) {
      return new String[]{};
    }
    TreeSet<String> ports = new TreeSet<>(comparator);
    for (String portName : portNames) {
      if (pattern.matcher(portName).find()) {
        ports.add(portName);
      }
    }
    return ports.toArray(new String[ports.size()]);
  }

  /**
   * Universal method for getting port names of _nix based systems
   */
  private static String[] getUnixBasedPortNames (String searchPath, Pattern pattern, Comparator<String> comparator) {
    searchPath = (searchPath.equals("") ? searchPath : (searchPath.endsWith("/") ? searchPath : searchPath + "/"));
    String[] returnArray = new String[]{};
    File dir = new File(searchPath);
    if (dir.exists() && dir.isDirectory()) {
      File[] files = dir.listFiles();
      if (files != null && files.length > 0) {
        TreeSet<String> portsTree = new TreeSet<>(comparator);
        for (File file : files) {
          String fileName = file.getName();
          if (!file.isDirectory() && !file.isFile() && pattern.matcher(fileName).find()) {
            String portName = searchPath + fileName;
            // For linux ttyS0..31 serial ports check existence by opening each of them
            if (fileName.startsWith("ttyS")) {
              long portHandle = serialInterface.openPort(portName, false);//Open port without TIOCEXCL
              if (portHandle < 0 && portHandle != SerialNativeInterface.ERR_PORT_BUSY) {
                continue;
              } else if (portHandle != SerialNativeInterface.ERR_PORT_BUSY) {
                serialInterface.closePort(portHandle);
              }
            }
            portsTree.add(portName);
          }
        }
        returnArray = portsTree.toArray(returnArray);
      }
    }
    return returnArray;
  }
}
