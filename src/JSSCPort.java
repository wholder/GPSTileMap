/**
 * Created by wholder on 10/30/15.
 *
 * Encapsulates JSSC functionality into an easy to use class
 * See: https://code.google.com/p/java-simple-serial-connector/
 * And: https://github.com/scream3r/java-simple-serial-connector/releases
 */

  // Note: does not seem to receive characters when using a serial adapter based on a Prolific chip (or clone)

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;

import jssc.*;

import javax.swing.*;

public class JSSCPort implements SerialPortEventListener {
  private static final Map<String,Integer> baudRates = new LinkedHashMap<>();
  private static final int    eventMasks = SerialPort.MASK_RXCHAR;                // Also, SerialPort.MASK_CTS, SerialPort.MASK_DSR
  private static final int    flowCtrl = SerialPort.FLOWCONTROL_NONE;
  private static final int    defaultBaud = 115200;
  private ArrayBlockingQueue<Integer>  queue = new ArrayBlockingQueue<>(1000);
  private Pattern             macPat = Pattern.compile("tty.*");
  private Preferences         prefs;
  private String              portName, prefId;
  private int                 baudRate, dataBits = 8, stopBits = 1, parity = 0;
  private SerialPort          serialPort;
  private RXEvent             rxHandler;
  private OpenClose           openCloseHandler;

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
    // Determine OS Type
    switch (SerialNativeInterface.getOsType()) {
      case SerialNativeInterface.OS_LINUX:
        macPat = Pattern.compile("(ttyS|ttyUSB|ttyACM|ttyAMA|rfcomm)[0-9]{1,3}");
        break;
      case SerialNativeInterface.OS_MAC_OS_X:
        macPat = Pattern.compile("tty.*");
        break;
      case SerialNativeInterface.OS_WINDOWS:
        macPat = Pattern.compile("");
        break;
      default:
        macPat = Pattern.compile("tty.*");
        break;
    }
    portName = prefs.get(prefId + ".serial.port", null);
    baudRate = prefs.getInt(prefId + ".serial.baud", defaultBaud);
  }

  public void setBaudRate (int rate) {
    prefs.putInt(prefId + ".serial.baud", baudRate = rate);
  }

  public void openPort () throws SerialPortException {
    for (String name : SerialPortList.getPortNames(macPat)) {
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
    for (String pName : SerialPortList.getPortNames(macPat)) {
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
}
