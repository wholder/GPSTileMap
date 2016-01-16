import d3.env.TSAGeoMag;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.filechooser.FileFilter;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.nio.file.FileSystemException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
 /*
  *  AVC      40.071000, -105.229500
  *  Erma     32.919083, -117.114692
  *  Sparkfun 40.090860, -105.185759
  *
  *
  * Note: Ramp dimensions 45" x 45" x 8"
  *       Hoop dimensions 60" wide, 42" tall
  *       Barrel dimensions 23" wide, 36" tall
  *       
  * OSX ~Library folder: Use Finder "Go to Folder" command in Go menu
  *   ~/Library/
  *   
  * Or, use this command in terminal:
  *   chflags nohidden ~/Library/
  *   
  * Map files are stored at following paths depending on the OS:
  *   windows:  C:/Users/~user/Library/GPSTileMap/
  *   Mac OSX   ~/Library/GPSTileMap/
  *
  * Settings (incliding the location of Obstacle markers) are stored using java.util.prefs.Preferences:
  *   windows   HKEY_CURRENT_USER\Software\JavaSoft\Prefs\/G/P/S/Tile/Map
  *   Max OSX   ~/Library/Preferences/com.apple.java.util.prefs.plist (Note: edit file to change)
  *   
  *   Note: The Preferences folder can be accessed holding down the option key while using the Finder �Go To Folder�
  *   command. Enter ~/Library/Preferences.
  *   
  *   Code Sign App (Russ' crash problem)
  *     codesign -s - --force GPSTileMap.app
  *
  *   Google Static Maps Key
  *     https://console.developers.google.com/apis/credentials?project=roboavr
  */

public class GPSTileMap extends JFrame implements ActionListener, Runnable {
  private static DecimalFormat declinationFmt = new DecimalFormat("#.#");
  private static char[]       hexVals = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'};
  private static int          OPEN_KEY  = KeyEvent.VK_O;
  private static int          CREATE_KEY  = KeyEvent.VK_C;
  private static int          QUIT_KEY  = KeyEvent.VK_Q;
  private static String       osName = System.getProperty("os.name").toLowerCase();
  private static String       userDir = System.getProperty("user.home") + "/Library/" + GPSTileMap.class.getName();
  private JMenuBar            menuBar;
  private JTabbedPane         tabs;
  private GPSMap              gpsMap;
  private CarLink             carLink;
  private JMenu               fileMenu;
  private JMenu               zoomMenu;
  private JMenu               optMenu;
  private JMenu               waypointMenu;
  private JMenu               settingsMenu;
  private JMenu               bumpMenu;
  private JButton             runStop;
  private JFileChooser        fc = new JFileChooser();
  private ButtonGroup         zoomGroup = new ButtonGroup();
  private boolean             expertMode;
  private JSSCPort            jsscPort;
  private transient Preferences prefs = Preferences.userRoot().node(this.getClass().getName());
  private static String       mapKey;
  private static TSAGeoMag    magModel;
  private volatile boolean    simRun;

  {
    // Clear out any old preferences so any stored objects can be regenerated
    if (!"2.1".equals(prefs.get("version", null))) {
      try {
        prefs.clear();
        prefs.put("version", "2.1");
      } catch (BackingStoreException ex) {
        ex.printStackTrace(System.out);
      }
    }
    mapKey = prefs.get("mapkey", "");
    if (mapKey == null  || mapKey.isEmpty()) {
      String key = JOptionPane.showInputDialog("Enter Map Key");
      if (key != null  && !key.isEmpty()) {
        prefs.put("mapkey", key);
        mapKey = key;
      } else {
        System.exit(0);
      }
    }
    fc.addChoosableFileFilter(new MyFileFilter("csv"));
    try {
      magModel = new TSAGeoMag();
    } catch (Exception ex) {
      showErrorDialog("Unable to load WMM.COF resource file.  Magnetic declination will not be available.");
    }
  }

  public static class LonLat implements Serializable {
    private static final long serialVersionUID = 7686575451237322227L;
    double                    lon, lat;

    public LonLat (double lon, double lat) {
      this.lon = lon;
      this.lat = lat;
    }

    public LonLat copy () {
      return new LonLat(lon, lat);
    }
  }

  public static class MyFileFilter extends FileFilter {
    private String  ext;

    public MyFileFilter (String ext) {
      this.ext = ext;
    }

    public boolean accept (File file) {
      return file.isDirectory() || ext.equals(getExtension(file));
    }

    public static String getExtension (File file) {
      String ext = null;
      String name = file.getName();
      int ii = name.lastIndexOf('.');
      if (ii > 0 &&  ii < name.length() - 1) {
        ext = name.substring(ii + 1).toLowerCase();
      }
      return ext;
    }

    public String getDescription () {
      return "." + ext + " Files";
    }
  }

  /*
   * Global Static Utility Methods
   */

  private static class MyToolBar extends JToolBar implements ActionListener, ChangeListener {
    private ButtonGroup group = new ButtonGroup();
    private String      state;
    private GPSMap      gpsMap;

    public MyToolBar () {
      super();
      add(getButton("arrow",    "arrow.png",      "Move",         "Move Marker", true));
      add(getButton("hand",     "hand.png",       "Drag",         "Drag Map"));
      add(getButton("cross",    "crosshair.png",  "Crosshair",    "GPS Coords"));
      add(getButton("tape",     "tape.png",       "Tape",         "Measure Distancee"));
      add(getButton("magnifier","magnifier.gif",  "Edit",         "Edit Waypoint or GPS Reference"));
      add(getButton("pin",      "pin.png",        "Pin",          "Set Waypoint"));
      add(getButton("barrel",   "barrel.png",     "Barrel",       "Place Barrel"));
      add(getButton("ramp",     "ramp.png",       "Ramp",         "Place Ramp"));
      add(getButton("hoop",     "hoop.png",       "Hoop",         "Place Hoop"));
      add(getButton("stanchion","stanchions.png", "stanchion",    "Place Stanchions"));
      add(getButton("gps",      "gpsRef.png",     "GPS",          "Place GPS Reference"));
      add(getButton("car",      "car.png",        "Car",          "Place Sim Car"));
      add(getButton("trash",    "trash.gif",      "Delete",       "Delete Waypoint or Marker"));
      setFloatable(false);
    }

    protected JRadioButton getButton (String cmd, String img, String altText, String toolTip) {
      return getButton(cmd, img, toolTip, altText, false);
    }

    protected JRadioButton getButton (String cmd, String img, String toolTip, String altText, boolean select) {
      URL imageURL = getClass().getResource("/images/" + img);
      JRadioButton button;
      if (imageURL != null) {
        button = new JRadioButton(new ImageIcon(imageURL));
      } else {
        button = new JRadioButton(altText);
      }
      group.add(button);
      button.setActionCommand(cmd);
      button.setToolTipText(toolTip);
      button.addActionListener(this);
      button.addChangeListener(this);
      button.setOpaque(true);
      button.setSelected(select);
      return button;
    }

    public void actionPerformed (ActionEvent ev) {
      state = ev.getActionCommand();
      if ("stanchion".equals(state)) {
        int last = gpsMap.markSet.markers.size() - 1;
        if (last > 0 && gpsMap.markSet.markers.get(last).type == GPSMap.MarkerType.POLY) {
          gpsMap.markSet.markers.add(new GPSMap.Marker(false));
        }
      }
      if (gpsMap != null)
        gpsMap.setTool(state);
    }

    public void stateChanged (ChangeEvent ev) {
      JRadioButton but = (JRadioButton) ev.getSource();
      but.setBackground(but.isSelected() ? Color.GRAY : Color.WHITE);
    }

    public void registerGPSMap (GPSMap gpsMap) {
      this.gpsMap = gpsMap;
    }
  }

  public static class GPSMap extends JPanel {
    private static Map<String,Color> colors = new HashMap<>();
    private static Map<Color,String> rColor = new HashMap<>();
    private static DecimalFormat  lonLatFmt = new DecimalFormat("#.0000000");
    private static DecimalFormat  feetFmt = new DecimalFormat("#.##");
    private static DecimalFormat  worldFmt = new DecimalFormat("#.##########");
    private static final int      tileSize = 256, imgTileSize = 512;
    private static final double   pixelsPerLonDegree = tileSize / 360.0;
    private static final double   pixelsPerLonRadian = tileSize / (2.0 * Math.PI);
    private static final double   originX = tileSize / 2.0, originY = tileSize / 2.0;
    private static final int      BaseZoom = 19;
    private static final int      MaxZoom = 21;
    private static Dimension[]    zoomLevels = {new Dimension(2048, 2048), new Dimension(4096, 4096), new Dimension(8192, 8192)};
    private MapSet                mapSet;
    private MarkSet               markSet;
    private transient Image       offScr;
    private Dimension             win, lastWin;
    private boolean               screenRotate;   // rotate 180 if true
    private JTextField            toolInfo;
    private int                   sX, sY, pX, pY, offX, offY;
    private int                   zoom;
    private int                   check;
    private boolean               showMarkers, moveMarkers, showNumbers, showSettings, showWayLines;
    private Point                 tapeStart, tapeEnd;
    private String                tool;
    private Drawable              selected;
    protected Settings            settings = new Settings();
    private transient Preferences prefs;
    private transient GPSTileMap  gpsTileMap;

    static {
      colors.put("red", Color.RED);
      colors.put("yellow", Color.YELLOW);
      colors.put("green", Color.GREEN);
      colors.put("blue", Color.BLUE);
      colors.put("orange", Color.ORANGE);
      colors.put("magenta", Color.MAGENTA);
      colors.put("cyan", Color.CYAN);
      colors.put("gray", Color.GRAY);
      colors.put("white", Color.WHITE);
      colors.put("black", Color.BLACK);

      rColor.put(Color.RED, "red");
      rColor.put(Color.YELLOW, "yellow");
      rColor.put(Color.GREEN, "green");
      rColor.put(Color.BLUE, "blue");
      rColor.put(Color.ORANGE, "orange");
      rColor.put(Color.MAGENTA, "magenta");
      rColor.put(Color.CYAN, "cyan");
      rColor.put(Color.GRAY, "gray");
      rColor.put(Color.WHITE, "white");
      rColor.put(Color.BLACK, "black");
    }

    private static class MapSet implements Serializable {
      private static final long   serialVersionUID = 6723304208987345944L;
      private String              name;
      private Point[]             ulLoc = new Point[3];
      private Point[]             mapLoc = new Point[3];
      private transient Image[]   maps = new Image[3];
      private byte[]              imgData;
      private LonLat              loc;

      public MapSet (String name, LonLat loc) {
        this.name = name;
        this.loc = loc;
        // Setup offsets for different zoom levels
        for (int ii = 0; ii < 3; ii++) {
          mapLoc[ii] = lonLanToPixel(loc, ii + BaseZoom);
          // Compute upper left corner of map
          ulLoc[ii] = new Point(mapLoc[ii].x - zoomLevels[ii].width / 2, mapLoc[ii].y  - zoomLevels[ii].height / 2);
        }
      }

      public double getDeclination () {
        if (magModel != null) {
          Calendar cal = Calendar.getInstance();
          int year = cal.get(Calendar.YEAR);
          int doy = cal.get(Calendar.DAY_OF_YEAR);
          double fracYear = (double) year + ((double) doy / 356.0);
          return magModel.getDeclination(loc.lat, loc.lon, fracYear, 0);
        }
        return 0;
      }

      public static MapSet loadMapSet (String name) throws IOException, ClassNotFoundException {
        File fName = new File(userDir + "/" + name + ".map");
        FileInputStream fileIn = new FileInputStream(fName);
        ObjectInputStream in = new ObjectInputStream(fileIn);
        return (MapSet) in.readObject();
     }

      public void saveMapSet () throws IOException {
        getMap(MaxZoom);
        File fName = new File(userDir + "/" + name + ".map");
        if (!fName.exists()) {
          FileOutputStream fileOut = new FileOutputStream(fName);
          ObjectOutputStream out = new ObjectOutputStream(fileOut);
          out.writeObject(this);
          out.close();
          fileOut.close();
        }
      }

      // Compress images in maps[] to PNG temporarily saved to imgData[] then serialize to output stream
      private void writeObject (ObjectOutputStream out) throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ImageIO.write((BufferedImage) maps[2], "png", bout);
        imgData = bout.toByteArray();
        out.defaultWriteObject();
        imgData = null;
     }

      // Read serialized PNG images into temp imgData[] then uncompress images into maps[]
      private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        maps = new Image[3];
        ByteArrayInputStream bin = new ByteArrayInputStream(imgData);
        maps[2] = ImageIO.read(bin);
        //FileOutputStream fOut = new FileOutputStream("/Users/wholder/Desktop/map.png");
        //fOut.write(imgData);
        //fOut.close();
      }

      private Image getMap (int zoom) {
        int base = zoom - BaseZoom;
        if (maps[base] == null) {
          BufferedImage img = new BufferedImage(zoomLevels[base].width, zoomLevels[base].height, BufferedImage.TYPE_INT_RGB);
          Graphics2D g1 = (Graphics2D) img.getGraphics();
          g1.drawImage(maps[2], 0, 0, zoomLevels[base].width, zoomLevels[base].height, 0, 0, zoomLevels[2].width, zoomLevels[2].height, null);
          maps[base] = img;
        }
        return maps[base];
      }

      private Point getUlLoc (int zoom) {
        return ulLoc[zoom - BaseZoom];
      }
    }

    private static class MarkSet implements Serializable {
      private static final long serialVersionUID = 7686575450447322227L;
      private String            name;
      private List<Marker>      markers;
      private List<Waypoint>    waypoints;
      private GPSReference      gpsReference;
      private SimCar            simCar;

      private MarkSet (String name) {
        this.name = name;
        if ("AVC".equals(name)) {
          resetMarkers();
        } else {
          markers = new ArrayList<>();
        }
        waypoints = new ArrayList<>();
      }

      private void clearWaypoints () {
        waypoints = new ArrayList<>();
      }

      public static MarkSet load (String name) {
        String fName = userDir + "/" + name + ".mrk";
        try {
          File file = new File(fName);
          if (file.exists()) {
            FileInputStream fileIn = new FileInputStream(file);
            ObjectInputStream in = new ObjectInputStream(fileIn);
            return (MarkSet) in.readObject();
          } else {
            return new MarkSet(name);
          }
        } catch (Exception ex) {
          System.out.println("Unable to save MarkSet: " + fName);
          return new MarkSet(name);
        }
      }

      public void save () {
        String fName = userDir + "/" + name + ".mrk";
        try {
          File file = new File(fName);
          FileOutputStream fileOut = new FileOutputStream(file);
          ObjectOutputStream out = new ObjectOutputStream(fileOut);
          out.writeObject(this);
          out.close();
          fileOut.close();
        } catch (IOException ex) {
          System.out.println("Unable to save MarkSet: " + fName);
        }
      }

      public void resetMarkers () {
        // Set markers to position values published by Sparkfun for AVC 2013
        markers = new ArrayList<>();
        /*
        markers.add(new Marker(MarkerType.CIRCLE, 40.0710390, -105.2299660, 23, Color.RED));      // Barrel 1
        markers.add(new Marker(MarkerType.CIRCLE, 40.0709820, -105.2299570, 23, Color.RED));      // Barrel 2
        markers.add(new Marker(MarkerType.CIRCLE, 40.0709009, -105.2299000, 23, Color.RED));      // Barrel 3
        markers.add(new Marker(MarkerType.CIRCLE, 40.0708050, -105.2298690, 23, Color.RED));      // Marker 4

        markers.add(new Marker(MarkerType.POLY,   40.0712589, -105.2300260, 12, Color.YELLOW));   // Cormer 1
        markers.add(new Marker(MarkerType.POLY,   40.0707559, -105.2297179, 12, Color.YELLOW));   // Cormer 2
        markers.add(new Marker(MarkerType.POLY,   40.0709769, -105.2291910, 12, Color.YELLOW));   // Cormer 3
        markers.add(new Marker(MarkerType.POLY,   40.0713319, -105.2294660, 12, Color.YELLOW));   // Cormer 4
        markers.add(new Marker(true));

        markers.add(new Marker(MarkerType.HOOP,   40.0708299, -105.2295309, 60, Color.GREEN, 60));// Hoop
        markers.add(new Marker(MarkerType.RECT,   40.0710810, -105.2291989, 45, Color.BLUE, 60)); // Ramp
        markers.add(new Marker(MarkerType.CIRCLE, 40.0713749, -105.2297889, 30, Color.WHITE));    // Start
        */
      }

      public void loadMarkers (String data) {
        markers = new ArrayList<>();
        StringTokenizer tok = new StringTokenizer(data, "\n\r");
        while (tok.hasMoreTokens()) {
          String line = tok.nextToken();
          String[] items = line.split(",");
          if (items.length == 1) {
            markers.add(new Marker(true));
          } else if (items.length >= 5) {
            MarkerType type = MarkerType.valueOf(items[0].trim());
            double lat = Double.valueOf(items[1].trim());
            double lon = Double.valueOf(items[2].trim());
            int radius = Integer.valueOf(items[3].trim());
            Color color = colors.get(items[4].trim().toLowerCase());
            color = color != null ? color : Color.ORANGE;
            if (items.length == 6) {
              markers.add(new Marker(type, new LonLat(lon, lat), radius, color, Integer.valueOf(items[5].trim())));
            } else {
              markers.add(new Marker(type, new LonLat(lon, lat), radius, color));
            }
          }
        }
      }

      public void saveMarkers (File save) {
        try {
          FileOutputStream fOut = new FileOutputStream(save);
          PrintWriter pOut = new PrintWriter(fOut);
          for (Marker mrk : markers) {
            pOut.print(mrk.type.toString());
            if (mrk.type != MarkerType.POLYCLOSE) {
              pOut.print(",");
              pOut.print(mrk.loc.lat);
              pOut.print(",");
              pOut.print(mrk.loc.lon);
              pOut.print(",");
              pOut.print(mrk.diameter);
              pOut.print(",");
              pOut.print(rColor.get(mrk.color));
              if (mrk.hasRotation) {
                pOut.print(",");
                pOut.print(mrk.rotation);
              }
            }
            pOut.println();
          }
          pOut.close();
          fOut.close();
        } catch (IOException ex) {
          ex.printStackTrace(System.out);
        }
      }

      private String getCsvCoords (Settings settings) {
        StringBuilder buf = new StringBuilder();
        for (Waypoint way : waypoints) {
          buf.append(lonLatFmt.format(way.loc.lat));
          buf.append(",");
          buf.append(lonLatFmt.format(way.loc.lon));
          buf.append(",");
          //buf.append(settings.getCode(way.sel));
          int tmp = settings.getCode(way.sel) & 0x0F;
          tmp |= (way.heading & 0x1FF) << 4;
          tmp |= way.avoidBarrels ? 0x8000 : 0;
          tmp |= way.jumpRamp ? 0x4000 : 0;
          tmp |= way.raiseFlag ? 0x2000 : 0;
          buf.append(tmp);
          buf.append("\n");
        }
        return buf.toString();
      }

      private void loadWaypoints (String vals, Settings settings) {
        waypoints = new ArrayList<>();
        StringTokenizer toks = new StringTokenizer(vals, "\n\r");
        while (toks.hasMoreTokens()) {
          String line = toks.nextToken();
          String[] items = line.split(",");
          double lat = Double.parseDouble(items[0]);
          double lon = Double.parseDouble(items[1]);
          int cmd = Integer.parseInt(items[2]);
          String code = settings.getDesc(cmd & 0x0F);
          Waypoint newWay = new Waypoint(new LonLat(lon, lat), code);
          if ((cmd & 0x8000) != 0)
            newWay.avoidBarrels = true;
          if ((cmd & 0x4000) != 0)
            newWay.jumpRamp = true;
          if ((cmd & 0x2000) != 0)
            newWay.raiseFlag = true;
          newWay.heading = (cmd >> 4) & 0x1FF;
          waypoints.add(newWay);
        }
      }
    }

    public enum MarkerType {
      CIRCLE, RECT, HOOP, POLY, POLYCLOSE, POLYEND, GPSREF
    }

    public static class Marker extends Drawable implements Serializable {
      private static final long  serialVersionUID = 7626575480447322227L;
      private MarkerType  type;
      private int         rotation;   // degrees (0-359)
      protected Color     color;
      private boolean     hasRotation;

      public Marker (MarkerType type, LonLat loc, int diameter, Color color) {
        super(loc, diameter);
        this.type = type;
        this.color = color;
      }

      public Marker (MarkerType type, LonLat loc, int diameter, Color color, int rotation) {
        this(type, loc, diameter, color);
        this.rotation = rotation;
        hasRotation = true;
      }

      public Marker (boolean closed) {
        super(new LonLat(0, 0), 0);
        type = closed ? MarkerType.POLYCLOSE : MarkerType.POLYEND;
      }

      public void doRotate (GPSTileMap.GPSMap gpsMap, int x, int y) {
        if (hasRotation) {
          Point mLoc = gpsMap.getMapLoc(loc);
          rotation = (int) Math.toDegrees(Math.toRadians(180) - Math.atan2(x - mLoc.x, y - mLoc.y)) % 360;
        }
      }

      protected Object[] doDraw (GPSTileMap.GPSMap gpsMap, Graphics2D g2, Object[] ret) {
        // Note: convert diameter from inches to pixels using factor of 2.2463006662281613 inches/pixel at zoom == 21
        int dia = (int) ((double) diameter / 2.2463006662281613) / (22 - gpsMap.zoom);
        g2.setColor(color);
        Point mLoc = gpsMap.getMapLoc(loc);
        g2.setStroke(new BasicStroke(1.0f));
        switch (type) {
          case CIRCLE: {
              g2.fillOval(mLoc.x - dia / 2, mLoc.y - dia / 2, dia, dia);
            }
            break;
          case RECT: {
              Graphics2D g2d = (Graphics2D)g2.create();
              g2d.rotate(Math.toRadians(rotation), mLoc.x, mLoc.y);
              g2d.fillRect(mLoc.x - dia / 2, mLoc.y - dia / 2, dia, dia);
            }
            break;
          case HOOP: {
              Graphics2D g2d = (Graphics2D)g2.create();
              g2d.rotate(Math.toRadians(rotation), mLoc.x, mLoc.y);
              g2d.fillRect(mLoc.x - dia / 2, mLoc.y - 1, dia, 3);
            }
            break;
          case POLY: {
            g2.fillOval(mLoc.x - dia / 2, mLoc.y - dia / 2, dia, dia);
            if (ret != null && ret.length == 2) {
              Point lp = (Point) ret[1];
              g2.drawLine(lp.x, lp.y, mLoc.x, mLoc.y);
              return new Object[] {color, lp, new Point(mLoc.x, mLoc.y)};
            } else if (ret != null && ret.length == 3) {
              Point fp = (Point) ret[1];
              Point lp = (Point) ret[2];
              g2.drawLine(lp.x, lp.y, mLoc.x, mLoc.y);
              return new Object[] {color, fp, new Point(mLoc.x, mLoc.y)};
            }
            return new Object[] {color, new Point(mLoc.x, mLoc.y)};
            }
          case POLYCLOSE: {
            // Note: ends definition of Stanchion chain and draws closing segment
            if (ret != null && ret.length == 3) {
              g2.setColor((Color) ret[0]);
              Point lp1 = (Point) ret[1];
              Point lp2 = (Point) ret[2];
              g2.drawLine(lp1.x, lp1.y, lp2.x, lp2.y);
            }
            } break;
          case POLYEND:
            // Marks end of Stanchion chain without closing segment
            break;
          case GPSREF: {
            // Diameter of icon does not scale with zoom
            dia = 20;
            int hDia = dia / 2;
            g2.drawLine(mLoc.x, mLoc.y - hDia, mLoc.x, mLoc.y - hDia / 2);
            g2.drawLine(mLoc.x, mLoc.y + hDia, mLoc.x, mLoc.y + hDia / 2);
            g2.drawLine(mLoc.x - hDia, mLoc.y, mLoc.x - hDia / 2, mLoc.y);
            g2.drawLine(mLoc.x + hDia, mLoc.y, mLoc.x + hDia / 2, mLoc.y);
            g2.setStroke(thick[21 - gpsMap.zoom]);
            g2.drawOval(mLoc.x - hDia, mLoc.y - hDia, dia, dia);
          } break;
        }
        return new Object[0];
      }
    }

    abstract public static class Drawable implements Serializable {
      private static final long serialVersionUID = 7586575480447322227L;
      protected static Stroke[] thick = {new BasicStroke(3.0f), new BasicStroke(2.0f), new BasicStroke(1.0f)};
      protected LonLat          loc;
      protected int             diameter;

      public Drawable (LonLat loc, int diameter) {
        this.loc = loc;
        this.diameter = diameter;
      }

      public boolean selects (GPSTileMap.GPSMap gpsMap, int x, int y) {
        Point mLoc = gpsMap.getMapLoc(loc);
        return (int) Math.sqrt(Math.pow((double) mLoc.x - x, 2) + Math.pow((double) mLoc.y - y, 2)) < diameter / (22 - gpsMap.zoom);
      }
    }

    public static class SimCar extends Drawable implements Serializable {
      private static final long serialVersionUID = 7686215450217322227L;
      private CarShape  carShape = new CarShape();
      private LonLat    saveLoc;
      private double    scale = 1.0, angle, saveAngle;
      private double    maxSteer = 30, maxSpeed;
      private double    speed, accel, decel;
      private int       wayIdx = 1;

      private class CarShape extends Polygon {
        private CarShape () {
          // Left side                  f
          addPoint(-4,  0);
          addPoint(-4,  1);
          addPoint(-5,  1);
          addPoint(-5,  3);
          addPoint(-4,  3);
          addPoint(-4,  9);
          addPoint(-5,  9);
          addPoint(-5, 11);
          addPoint(-4, 11);
          addPoint(-4, 12);
          // Right side
          addPoint( 4, 12);
          addPoint( 4, 11);
          addPoint( 5, 11);
          addPoint( 5,  9);
          addPoint( 4,  9);
          addPoint( 4,  3);
          addPoint( 5,  3);
          addPoint( 5,  1);
          addPoint( 4,  1);
          addPoint( 4,  0);
        }
      }

      public SimCar (LonLat loc) {
        super(loc, 30);
        saveLoc = loc.copy();
        decel = accel = 0.0000057419 / 150;
        maxSpeed = 0.0000057419 / 4;
      }

      public void reset () {
        loc = saveLoc.copy();
        angle = saveAngle;
        speed = 0;
        wayIdx = 1;
      }

      public void doDraw (GPSTileMap.GPSMap gpsMap, Graphics2D g2) {
        Point mLoc = gpsMap.getMapLoc(loc);
        // Rotate car shape to reflect steering angle and draw
        AffineTransform at = AffineTransform.getTranslateInstance(mLoc.x, mLoc.y);
        at.rotate(Math.toRadians((angle + 180.0) % 360.0));
        int div = 1 << (2 - (gpsMap.zoom - BaseZoom));
        double dScale = scale / div;
        at.scale(dScale, dScale);
        g2.setStroke(new BasicStroke(2.0f / div));
        g2.setColor(Color.GREEN);
        g2.fill(at.createTransformedShape(carShape));
        g2.setStroke(new BasicStroke(1.0f / div));
        g2.setColor(Color.BLACK);
        g2.draw(at.createTransformedShape(carShape));
      }

      /**
       * Update simulated robot car's position
       * @param simRun true if simulation is enabled
       * @return true if car has reached next waypoint
       */
      public boolean doMove (LonLat wayLon, LonLat prevLon, boolean simRun) {
        // Convert LonLat Coords into World Coordinates
        Point.Double prevWay = lonLatToWorld(prevLon);
        Point.Double wayPnt = lonLatToWorld(wayLon);
        Point2D.Double position = lonLatToWorld(loc);
        // Next values in World Units
        double length = 0.0000057419;       // Length of car
        double wayRadius = 0.0000305325;    // Radius of waypoint trip point
        double ciRadius = 0.0000305325;     // Radius of intersect circle around car's pivot point
        if (speed < 0)
          throw new IllegalArgumentException("Move < 0 not supported");
        double steerAngle;
        // Compute steering using line circle intersection
        Point2D.Double[] ret = intersect(prevWay, wayPnt, position, ciRadius);
        Point2D.Double ciPos = ret.length < 3 ? ret[0] : ret[1];
        double tmp = addAngle(angleTo(position, ciPos), -this.angle);
        if (tmp > 180)
          tmp = -(360 - tmp);
        steerAngle = Math.max(-maxSteer, Math.min(maxSteer, tmp));
        // Drive around pivot point (approximates bicycle steering)
        double radius = (1 / Math.tan(Math.abs(steerAngle) * Math.PI / 180)) * length;
        double pRad = Math.toRadians(steerAngle < 0 ? angle : addAngle(angle, 180));
        // Compute pivot point
        Point.Double pivot = new Point.Double(position.x - Math.cos(pRad) * radius, position.y - Math.sin(pRad) * radius);
        // Compute angle rotated around pivot proportional to distance
        double circum = radius * 2 * Math.PI;
        double tAngle = speed / circum * 360 * (steerAngle > 0 ? 1 : -1);
        // Rotate car's position around pivot point
        position = rotatePoint(position, pivot, tAngle);
        // Update car's facing angle
        angle = addAngle(angle, tAngle);
        // Update car's newly-computed World position into LonLat coords
        loc = worldToLonLat(position);
        // Update speed using accel value
        if (simRun) {
          speed = Math.min(maxSpeed, speed + accel);
        } else {
          speed = Math.max(0, speed - decel);
        }
        // See if car has reached next waypoint
        double remDist = distanceToGoal(prevWay, wayPnt, position);
        return remDist < wayRadius;
      }

      public void doRotate (GPSTileMap.GPSMap gpsMap, int x, int y) {
        Point mLoc = gpsMap.getMapLoc(loc);
        saveAngle = angle = Math.toDegrees(Math.toRadians(180) - Math.atan2(x - mLoc.x, y - mLoc.y)) % 360.0;
      }

      /**
       * Computes the two intersection points between a circle of a defined radius around point c
       * and a line drawn though two points, a and b, and/or the tangent point closest to the line
       * from point c.
       * @param a Starting point
       * @param b Destination point
       * @param c Location of Car
       * @param radius radius of intersect circle
       * @return Point[3] size array, where:
       *          Point[0] is circle intesection closest to Starting point
       *          Point[1] is circle intesection closest to Ending point
       *          Point[2] is tangent point on line
       *  or Point[1] size array, where
       *          Point[0] is tangent point on line
       */
      private Point2D.Double[] intersect (Point2D.Double a, Point2D.Double b, Point2D.Double c, double radius) {
        double dx1 = b.x - a.x;
        double dy1 = b.y - a.y;
        // compute the euclidean distance between A and B
        double lab = Math.sqrt(dx1 * dx1 + dy1 * dy1);
        // compute the direction vector D from A to B
        double dvx = dx1 / lab;
        double dvy = dy1 / lab;
        // Now the line equation is x = Dx*t + Ax, y = Dy*t + Ay with 0 <= t <= 1.
        double t = dvx * (c.x - a.x) + dvy * (c.y - a.y);
        // compute the coordinates of the point E on line and closest to C
        double ex = t * dvx + a.x;
        double ey = t * dvy + a.y;
        // compute the euclidean distance from E to C
        double dx2 = ex - c.x;
        double dy2 = ey - c.y;
        double lec = Math.sqrt(dx2 * dx2 + dy2 * dy2);
        // test if the line intersects the circle
        if (lec < radius) {
          // compute distance from t to circle intersection point
          double dt = Math.sqrt(radius * radius - lec * lec);
          // return first and second intersection points
          return new Point2D.Double[] {new Point2D.Double((t - dt) * dvx + a.x, (t - dt) * dvy + a.y),
              new Point2D.Double((t + dt) * dvx + a.x, (t + dt) * dvy + a.y),
              new Point2D.Double(ex, ey)};
        }
        // tangent point to circle is E
        return new Point2D.Double[] {new Point2D.Double(ex, ey),};
      }

      /**
       * Compute distance to goal as (dist from a to b) - (dist from a to t)
       * @param a Starting point
       * @param b Destination point
       * @param c Location of Car
       * @return postive dstance to goal, or negative distance past goal
       */
      private static double distanceToGoal (Point.Double a, Point.Double b, Point.Double c) {
        double dx1 = b.x - a.x;
        double dy1 = b.y - a.y;
        double dst1 = Math.sqrt(dx1 * dx1 + dy1 * dy1);
        double dx2 = a.x - c.x;
        double dy2 = a.y - c.y;
        double dst2 = Math.sqrt(dx2 * dx2 + dy2 * dy2);
        return dst1 - dst2;
      }

      private double angleTo (Point2D.Double car, Point2D.Double way) {
        // Note: y axis is reversed on screen
        double angle = Math.toDegrees(Math.atan2(way.x - car.x, car.y - way.y));
        angle = angle < 0 ? angle + 360 : angle;
        return angle;
      }

      private double addAngle (double angle, double add) {
        angle += add;
        if (angle > 360)
          return angle - 360;
        if (angle < 0)
          return angle + 360;
        return angle;
      }

      /**
       * Rotates one point around another
       * @param point The point to rotate.
       * @param center The centre point of rotation.
       * @param angle The rotation angle in degrees.
       * @return Rotated point
       */
      private Point2D.Double rotatePoint (Point2D.Double point, Point2D.Double center, double angle) {
        double radians = angle * (Math.PI / 180);
        double cosTheta = Math.cos(radians);
        double sinTheta = Math.sin(radians);
        return new Point2D.Double(
            (cosTheta * (point.x - center.x) - sinTheta * (point.y - center.y) + center.x),
            (sinTheta * (point.x - center.x) + cosTheta * (point.y - center.y) + center.y)
        );
      }
    }

    public static class Waypoint extends Drawable implements Serializable {
      private static final long  serialVersionUID = 7686575480337322227L;
      protected String   sel;
      protected boolean  avoidBarrels, jumpRamp, raiseFlag;
      protected int      heading;

      public Waypoint (LonLat loc, String sel) {
        super(loc, 25);
        this.sel = sel;
      }

      protected Object[] doDraw (GPSTileMap.GPSMap gpsMap, Graphics2D g2, Waypoint way, int num, Object[] ret) {
        int dia = diameter / (22 - gpsMap.zoom);
        int hDia = dia / 2;
        g2.setColor(Color.WHITE);
        Point mLoc = gpsMap.getMapLoc(loc);
        g2.drawLine(mLoc.x, mLoc.y - hDia, mLoc.x, mLoc.y - hDia / 2);
        g2.drawLine(mLoc.x, mLoc.y + hDia, mLoc.x, mLoc.y + hDia / 2);
        g2.drawLine(mLoc.x - hDia, mLoc.y, mLoc.x - hDia / 2, mLoc.y);
        g2.drawLine(mLoc.x + hDia, mLoc.y, mLoc.x + hDia / 2, mLoc.y);
        g2.setStroke(thick[21 - gpsMap.zoom]);
        g2.drawOval(mLoc.x - hDia, mLoc.y - hDia, dia, dia);
        StringBuilder buf = new StringBuilder();
        if (gpsMap.showNumbers)
          buf.append(Integer.toString(num));
        if (gpsMap.showSettings) {
          if (gpsMap.showNumbers)
            buf.append("-");
          buf.append(way.sel);
        }
        if (gpsMap.showNumbers) {
          g2.setColor(Color.BLACK);
          g2.drawString(buf.toString(), mLoc.x + hDia + 1, mLoc.y - hDia + 1);
          g2.setColor(Color.WHITE);
          g2.drawString(buf.toString(), mLoc.x + hDia, mLoc.y - hDia);
        }
        if (gpsMap.showWayLines) {
          g2.setStroke(new BasicStroke(1.0f));
          if (ret != null && ret.length == 1) {
            Point lp = (Point) ret[0];
            g2.drawLine(lp.x, lp.y, mLoc.x, mLoc.y);
            return new Object[] {lp, new Point(mLoc.x, mLoc.y)};
          } else if (ret != null && ret.length == 2) {
            Point fp = (Point) ret[0];
            Point lp = (Point) ret[1];
            g2.drawLine(lp.x, lp.y, mLoc.x, mLoc.y);
            return new Object[] {fp, new Point(mLoc.x, mLoc.y)};
          }
          return new Object[] {new Point(mLoc.x, mLoc.y)};
        }
        return null;
      }
    }

    public static class GPSReference extends Marker implements Serializable {
      private static final long     serialVersionUID = 7686575480447322227L;
      private double  refLat, refLon;

      public GPSReference (LonLat loc) {
        super(MarkerType.GPSREF, loc, 20, Color.ORANGE);
      }

      public void setLoc (double refLat, double refLon) {
        this.refLat = refLat;
        this.refLon = refLon;
      }

      public int getDeltaLat () {
        return toFixed(refLat - loc.lat);
      }

      public int getDeltaLon () {
        return toFixed(refLon - loc.lon);
      }
   }

    public static class Settings implements Serializable {
      private static final long     serialVersionUID = 7686575480447311127L;
      protected Map<String,Integer> wayVals;
      protected Map<Integer,String> wayDesc;
      protected String              wayDefault;

      protected Settings () {
        wayVals = new LinkedHashMap<>();
        wayVals.put("Stop", 0);
        wayVals.put("Slow", 1);
        wayVals.put("Medium", 2);
        wayVals.put("Fast", 3);
        wayDefault = "Slow";
        updateDesc();
      }

      private void updateDesc () {
        wayDesc = new LinkedHashMap<>();
        for (String desc : wayVals.keySet()) {
          Integer val = wayVals.get(desc);
          wayDesc.put(val, desc);
        }
      }

      protected String getDesc (int code) {
        return wayDesc.get(code);
      }

      protected int getCode (String desc) {
        return wayVals.containsKey(desc) ? wayVals.get(desc) : 0;
      }

      protected String getDefault () {
        return wayDefault;
      }

      protected void setDefault (String wayDefault) {
        this.wayDefault = wayDefault;
      }

      private String getSettings () {
        StringBuilder buf = new StringBuilder();
        for (String code : wayVals.keySet()) {
          String val = wayVals.get(code).toString();
          buf.append(code);
          buf.append(",");
          buf.append(val);
          buf.append("\n");
        }
        return buf.toString();
      }

      private void setSettings (String data) {
        wayVals = new LinkedHashMap<>();
        StringTokenizer tok = new StringTokenizer(data, "\n");
        while (tok.hasMoreTokens()) {
          String line = tok.nextToken();
          String[] parts = line.split(",");
          if (parts.length == 2  &&  parts[0].length() > 0) {
            wayVals.put(parts[0], new Integer(parts[1]));
          }
        }
        updateDesc();
      }
    }

    // Utility methods

    public Drawable findDrawable (int x, int y) {
      for (Drawable mrk : markSet.waypoints) {
        if (mrk.selects(this, x, y)) {
          return mrk;
        }
      }
      for (Drawable mrk : markSet.markers) {
        if (mrk.selects(this, x, y)) {
          return mrk;
        }
      }
      if (markSet.gpsReference != null &&  markSet.gpsReference.selects(this, x, y)) {
        return markSet.gpsReference;
      }
      if (markSet.simCar != null &&  markSet.simCar.selects(this, x, y)) {
        return markSet.simCar;
      }
      return null;
    }

    public boolean touches (Drawable mrk, int x, int y) {
      return mrk != null && mrk.selects(this, x, y);
    }

    private void setPosition (Drawable mrk, int x, int y) {
      mrk.loc = getMapLonLat(x, y);
    }

    public void setTool (String tool) {
      this.tool = tool;
      if ("hand".equals(tool)) {
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      //} else if ("cross".equals(tool)) {
      //    setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
      } else {
        setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
      }
    }

    private static boolean notEmpty (String val) {
      return val != null  &&  val.length() > 0;
    }

    public Point rotate (Point pos) {
      if (screenRotate) {
        pos.x = win.width - pos.x;
        pos.y = win.height - pos.y;
      }
      return pos;
    }

    class MyMouseAdapter extends MouseAdapter  {
      public void mousePressed (MouseEvent event) {
        if (mapSet == null) {
          toolInfo.setText("Map not loaded");
          return;
        }
        Point mp = rotate(new Point(event.getX(), event.getY()));
        if ("arrow".equals(tool)) {
          Drawable mrk = findDrawable(mp.x, mp.y);
          if (mrk instanceof Waypoint || (mrk instanceof Marker && moveMarkers) ||
              mrk instanceof GPSReference || mrk instanceof SimCar) {
            selected = mrk;
          }
        } else if ("hand".equals(tool)) {
          sX = mp.x;
          sY = mp.y;
          pX = offX;
          pY = offY;
        } else if ("cross".equals(tool)) {
          LonLat loc = getMapLonLat(mp.x, mp.y);
          toolInfo.setText(lonLatFmt.format(loc.lat) + ", " + lonLatFmt.format(loc.lon));
        } else if ("magnifier".equals(tool)) {
          Drawable mrk = findDrawable(mp.x, mp.y);
          if (mrk != null  &&  mrk instanceof Waypoint) {
            Waypoint way = (Waypoint) mrk;
            int num = markSet.waypoints.indexOf(way) + 1;
            JCheckBox chk1 = new JCheckBox("Avoid Barrels");
            chk1.setSelected(way.avoidBarrels);
            JCheckBox chk2 = new JCheckBox("Jump Ramp");
            chk2.setSelected(way.jumpRamp);
            JCheckBox chk3 = new JCheckBox("Raise Victory Flag");
            chk3.setSelected(way.raiseFlag);
            JTextField opt = new JTextField(Integer.toString(way.heading));
            Set<String> strings = settings.wayVals.keySet();
            Object[] choices = strings.toArray(new String[strings.size()]);
            Object[][] message;
            if (gpsTileMap.expertMode) {
              message = new Object[][]{{null, chk1}, {null, chk2}, {null, chk3}, {"Heading", opt}, {"Speed:"}};
            } else {
              message = new Object[][]{{"Speed:"}};
            }
            String ret = (String) JOptionPane.showInputDialog(null, message, "Select Waypoint " + num + " Speed Option",
                JOptionPane.PLAIN_MESSAGE, null, choices, way.sel);
            if (ret != null) {
              way.sel = ret;
              if (gpsTileMap.expertMode) {
                way.avoidBarrels = chk1.isSelected();
                way.jumpRamp = chk2.isSelected();
                way.raiseFlag = chk3.isSelected();
                try {
                  way.heading = Integer.parseInt(opt.getText());
                  if (way.heading < 0 || way.heading > 359) {
                    JOptionPane.showMessageDialog(null, "Invalid range for \"Heading\" field (0-359)",
                        "Error", JOptionPane.PLAIN_MESSAGE);
                  }
                } catch (Exception ex) {
                  JOptionPane.showMessageDialog(null, "Invalid value for \"Heading\" field", "Error", JOptionPane.PLAIN_MESSAGE);
                  return;
                }
              } else {
                way.avoidBarrels = false;
                way.jumpRamp = false;
                way.raiseFlag = false;
                way.heading = 0;
              }
              repaint();
            }
          } else if (mrk instanceof GPSReference) {
            JTextField lat = new JTextField();
            lat.setText(Double.toString(markSet.gpsReference.refLat));
            Object[][] message = {{"True Latitude:", lat}, {"True Longitude:"}};
            String lonTxt = (String) JOptionPane.showInputDialog(null, message,
                "GPS Benchmark", JOptionPane.PLAIN_MESSAGE,
                null, null, Double.toString(markSet.gpsReference.refLon));
            String latTxt = lat.getText();
            if (notEmpty(latTxt) && notEmpty(lonTxt)) {
              // Save coordinates to GPS Reference
              markSet.gpsReference.setLoc(toDouble(latTxt), toDouble(lonTxt));
              toolInfo.setText("dLat: " + lonLatFmt.format(markSet.gpsReference.refLat - markSet.gpsReference.loc.lat) +
                  ", dLon: " + lonLatFmt.format(markSet.gpsReference.refLon - markSet.gpsReference.loc.lon));
              repaint();
            } else if (lonTxt != null) {
              JOptionPane.showMessageDialog(null, "Must provide lat and lon values");
            }
          }
        } else if ("pin".equals(tool)) {
          LonLat loc = getMapLonLat(mp.x, mp.y);
          markSet.waypoints.add(new Waypoint(loc, settings.getDefault()));
          toolInfo.setText(lonLatFmt.format(loc.lat) + ", " + lonLatFmt.format(loc.lon));
          repaint();
        } else if ("barrel".equals(tool)) {
          LonLat loc = getMapLonLat(mp.x, mp.y);
          markSet.markers.add(new Marker(MarkerType.CIRCLE, loc, 23, Color.RED));
          toolInfo.setText(lonLatFmt.format(loc.lat) + ", " + lonLatFmt.format(loc.lon));
          repaint();
        } else if ("ramp".equals(tool)) {
          LonLat loc = getMapLonLat(mp.x, mp.y);
          Marker ramp = new Marker(MarkerType.RECT, loc, 45, Color.BLUE, 60);
          selected = ramp;
          markSet.markers.add(ramp);
          toolInfo.setText(lonLatFmt.format(loc.lat) + ", " + lonLatFmt.format(loc.lon));
          repaint();
        } else if ("hoop".equals(tool)) {
          LonLat loc = getMapLonLat(mp.x, mp.y);
          Marker hoop = new Marker(MarkerType.HOOP, loc, 60, Color.GREEN, 60);
          selected = hoop;
          markSet.markers.add(hoop);
          toolInfo.setText(lonLatFmt.format(loc.lat) + ", " + lonLatFmt.format(loc.lon));
          repaint();
        } else if ("stanchion".equals(tool)) {
          Marker first = null;
          // Find first Stanchion in chain
          for (int ii = markSet.markers.size() - 1; ii >= 0; ii--) {
            Marker tmp = markSet.markers.get(ii);
            if (tmp.type == MarkerType.POLY)
              first = tmp;
            else
              break;
          }
          if (touches(first, mp.x, mp.y)) {
            markSet.markers.add(new Marker(true));
          } else {
            LonLat loc = getMapLonLat(mp.x, mp.y);
            markSet.markers.add(new Marker(MarkerType.POLY, loc, 12, Color.YELLOW));
            toolInfo.setText(lonLatFmt.format(loc.lat) + ", " + lonLatFmt.format(loc.lon));
          }
          repaint();
        } else if ("gps".equals(tool)) {
          LonLat loc = getMapLonLat(mp.x, mp.y);
          markSet.gpsReference = new GPSReference(loc);
          repaint();
        } else if ("car".equals(tool)) {
          LonLat loc = getMapLonLat(mp.x, mp.y);
          selected = markSet.simCar = new SimCar(loc);
          gpsTileMap.runStop.setEnabled(true);
          repaint();
        } else if ("trash".equals(tool)) {
          Drawable mkr = findDrawable(mp.x, mp.y);
          if (mkr instanceof Waypoint) {
            markSet.waypoints.remove(mkr);
            repaint();
          } else if (mkr instanceof GPSReference) {
            markSet.gpsReference = null;
            repaint();
          } else if (mkr instanceof Marker) {
            markSet.markers.remove(mkr);
            repaint();
          } else if (mkr instanceof SimCar) {
            markSet.simCar = null;
            gpsTileMap.runStop.setEnabled(false);
            repaint();
          } else {
            toolInfo.setText("Not found");
          }
        } else if ("tape".equals(tool)) {
          Drawable mkr = findDrawable(mp.x, mp.y);
          if (mkr != null  &&  mkr instanceof Waypoint) {
            boolean first = true;
            double feet = 0;
            LonLat lst = null;
            for (Waypoint way : markSet.waypoints) {
              if (first) {
                lst = way.loc;
                first = false;
              } else {
                LonLat nxt = way.loc;
                feet += distanceInFeet(lst, nxt);
                lst = nxt;
              }
              toolInfo.setText("Total waypoint distance is " + feetFmt.format(feet) + " feet");
            }
          } else {
            tapeStart = new Point(mp.x, mp.y);
          }
        }
      }

      public void mouseReleased (MouseEvent event) {
        if ("arrow".equals(tool)) {
          if (selected != null) {
            repaint();
            selected = null;
          }
        } else if ("hand".equals(tool)) {
          prefs.putInt("window.offX", offX);
          prefs.putInt("window.offY", offY);
        } else if ("tape".equals(tool)) {
          if (tapeStart != null) {
            tapeStart = null;
            tapeEnd = null;
            repaint();
          }
        }
      }
    }

    class MyMouseMotionAdapter extends MouseMotionAdapter  {
      public void mouseDragged (MouseEvent event) {
        Point mp = rotate(new Point(event.getX(), event.getY()));
        boolean shiftDown = event.isShiftDown();
        if ("arrow".equals(tool) || "ramp".equals(tool) || "hoop".equals(tool) || "car".equals(tool)) {
          if (selected != null) {
            if (shiftDown && selected instanceof Marker) {
              ((Marker) selected).doRotate(gpsTileMap.gpsMap, mp.x, mp.y);
            } else if (shiftDown && selected instanceof SimCar) {
              ((SimCar) selected).doRotate(gpsTileMap.gpsMap, mp.x, mp.y);
            } else {
              setPosition(selected, mp.x, mp.y);
            }
            repaint();
          }
        } else if ("hand".equals(tool)) {
          win = getSize();
          int dX = sX - mp.x;
          int dY = sY - mp.y;
          int base = zoom - BaseZoom;
          offX = Math.max(0, Math.min(zoomLevels[base].width - win.width, pX + dX));
          offY = Math.max(0, Math.min(zoomLevels[base].height - win.height, pY + dY));
          repaint();
        } else if ("tape".equals(tool)) {
          tapeEnd = new Point(mp.x, mp.y);
          if (shiftDown) {
            Point.Double loc1 = lonLatToWorld(getMapLonLat(tapeStart.x, tapeStart.y));
            Point.Double loc2 = lonLatToWorld(getMapLonLat(tapeEnd.x, tapeEnd.y));
            double dx = Math.abs(loc1.x - loc2.x);
            double dy = Math.abs(loc1.y - loc2.y);
            double dist = Math.sqrt(dx * dx + dy * dy);
            toolInfo.setText("" + worldFmt.format(dist) + " World Units");

          } else {
            LonLat loc1 = getMapLonLat(tapeStart.x, tapeStart.y);
            LonLat loc2 = getMapLonLat(tapeEnd.x, tapeEnd.y);
            double lat1 = degreesToRadians(loc1.lat);
            double lon1 = degreesToRadians(loc1.lon);
            double lat2 = degreesToRadians(loc2.lat);
            double lon2 = degreesToRadians(loc2.lon);
            // Note: Radius of Earh in kilometers is 6371
            double dist = Math.acos(Math.sin(lat2) * Math.sin(lat1) + Math.cos(lat2) * Math.cos(lat1) * Math.cos(lon1 - lon2)) * 6371;
            // Note: 1 kilometer is 3280.84 feet
            toolInfo.setText("Distance is " + feetFmt.format(dist * 3280.84) + " feet");
          }
          repaint();
        }
      }
    }

    public GPSMap (GPSTileMap gpsTileMap, Preferences prefs, JTextField toolInfo) {
      this.gpsTileMap = gpsTileMap;
      this.prefs = prefs;
      this.toolInfo = toolInfo;
      setBackground(Color.white);
      addMouseListener(new MyMouseAdapter());
      addMouseMotionListener(new MyMouseMotionAdapter());
      offX = prefs.getInt("window.offX", 373);
      offY = prefs.getInt("window.offY", 480);
      zoom = prefs.getInt("window.zoom", 20);
    }

    public void changeZoom (int newZoom) {
      int oldIdx = zoom - BaseZoom;
      int newIdx = newZoom - BaseZoom;
      int hWid = win.width / 2;
      int hHyt = win.height / 2;
      double dx = (double) (offX + hWid) / zoomLevels[oldIdx].width;
      double dy = (double) (offY + hHyt) / zoomLevels[oldIdx].height;
      offX = (int) (dx * zoomLevels[newIdx].width) - hWid;
      offY = (int) (dy * zoomLevels[newIdx].height) - hHyt;
      offX = Math.max(0, Math.min(zoomLevels[newIdx].width - win.width, offX));
      offY = Math.max(0, Math.min(zoomLevels[newIdx].height - win.height, offY));
      this.zoom = newZoom;
      prefs.putInt("window.zoom", zoom);
      prefs.putInt("window.offX", offX);
      prefs.putInt("window.offY", offY);
    }

    protected Point getMapLoc (LonLat loc) {
      Point pLoc = lonLanToPixel(loc, zoom);
      return new Point(pLoc.x - (mapSet.getUlLoc(zoom).x + offX), pLoc.y - (mapSet.getUlLoc(zoom).y + offY));
    }

    protected LonLat getMapLonLat (int mx, int my) {
      Point pLoc = new Point(mapSet.getUlLoc(zoom).x + offX + mx, mapSet.getUlLoc(zoom).y + offY + my);
      return GPSMap.pixelToLonLat(pLoc,  zoom);
    }

    private void saveObject (String name, Object obj) {
      try {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(bout);
        out.writeObject(obj);
        prefs.putByteArray(name, bout.toByteArray());
      } catch (Exception ex) {
        ex.printStackTrace(System.out);
      }
    }

    public void resetSettings () {
      settings = new Settings();
      persistSettings();
      clearWaypoints();
    }


    public void saveSettings (String data) {
      settings.setSettings(data);
      persistSettings();
      clearWaypoints();
    }

    public void initSettiings () {
      byte[] tmp = prefs.getByteArray("settings", null);
      if (tmp != null) {
        try {
          settings = (Settings) (new ObjectInputStream(new ByteArrayInputStream(tmp))).readObject();
        } catch (Exception ex) {
          prefs.remove("settings");
        }
      }
      if (settings == null){
        resetSettings();
      }
    }

    public void persistSettings () {
      // Save markers into persistent preference data
      saveObject("settings", settings);
    }

    public void clearWaypoints () {
      markSet.clearWaypoints();
      repaint();
    }

    public void loadMap (String mapName) throws Exception {
      setMap(GPSMap.MapSet.loadMapSet(mapName));
    }

    public void setMap (MapSet mapSet) {
      this.mapSet = mapSet;
      setTool("arrow");
      screenRotate = prefs.getBoolean("rotate.on", false);
      showMarkers = prefs.getBoolean("markers.on", true);
      moveMarkers = prefs.getBoolean("move_markers.on", false);
      showNumbers = prefs.getBoolean("numbers.on", false);
      showSettings = prefs.getBoolean("settings.on", false);
      showWayLines = prefs.getBoolean("waylines.on", true);
      markSet = MarkSet.load(mapSet.name);
      initSettiings();
      gpsTileMap.runStop.setEnabled(markSet.simCar != null);
      repaint();
    }

    void saveMarkers () {
      if (markSet != null){
        markSet.save();
      }
    }

    public void paint (Graphics g) {
      win = getSize();
      if (offScr == null  ||  (lastWin != null  &&  (win.width != lastWin.width  ||  win.height != lastWin.height))) {
        offScr = createImage(win.width, win.height);
      }
      lastWin = win;
      Graphics2D g2 = (Graphics2D) offScr.getGraphics();
      if (screenRotate) {
        g2.rotate(Math.toRadians(180));
        g2.translate(-win.width, -win.height);
      }
      g2.setBackground(getBackground());
      if (mapSet != null) {
        g2.setRenderingHints(new RenderingHints(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON));
        g2.drawImage(mapSet.getMap(zoom), 0, 0, win.width, win.height, offX, offY, offX + win.width, offY + win.height, this);
        if (markSet != null) {
          if (showMarkers) {
            // Draw objects on Map
            Object[] ret = null;
            for (Marker mrk : markSet.markers) {
              ret = mrk.doDraw(this, g2, ret);
            }
          }
          // Draw waypoints on Map
          int num = 1;
          Object[] ret = null;
          for (Waypoint way : markSet.waypoints) {
            ret = way.doDraw(this, g2, way, num++, ret);
          }
          if (markSet.gpsReference != null) {
            markSet.gpsReference.doDraw(this, g2, ret);
          }
          if (markSet.simCar != null) {
            markSet.simCar.doDraw(this, g2);
          }
        }
      }
      if (tapeStart != null  &&  tapeEnd != null) {
        g2.setColor(Color.WHITE);
        g2.drawLine(tapeStart.x, tapeStart.y, tapeEnd.x, tapeEnd.y);
      }
      g.drawImage(offScr, 0, 0, this);
    }

    public String[] getUploadData () {
      List<String> lines = new ArrayList<>();
      lines.add("z\n\r");
      // Send Map's declination value to car
      int dec = (int) (mapSet.getDeclination() + .5);
      StringBuilder buf = new StringBuilder("@");
      check = 0;
      buf.append(toHexByte(dec, 2));
      buf.append(toHexByte(check, 2));
      buf.append("\n\r");
      lines.add(buf.toString());
      // Send GPS Offset (if defined) to car
      if (markSet.gpsReference != null) {
        check = 0;
        buf = new StringBuilder("#");
        // Output Offset (ref - marker)
        buf.append(toHexByte(markSet.gpsReference.getDeltaLat(), 8));
        buf.append(toHexByte(markSet.gpsReference.getDeltaLon(), 8));
        // Output Marker Coords (location on map)
        buf.append(toHexByte(toFixed(markSet.gpsReference.loc.lat), 8));
        buf.append(toHexByte(toFixed(markSet.gpsReference.loc.lon), 8));
        // Output Reference Coords (from dialog)
        buf.append(toHexByte(toFixed(markSet.gpsReference.refLat), 8));
        buf.append(toHexByte(toFixed(markSet.gpsReference.refLon), 8));
        // Output Checksum
        buf.append(toHexByte(check, 2));
        buf.append("\n\r");
        lines.add(buf.toString());
      }
      // Send coords to car
      int num = 0;
      for (GPSMap.Waypoint way : markSet.waypoints) {
        check = 0;
        int lat = toFixed(way.loc.lat);
        int lon = toFixed(way.loc.lon);
        buf = new StringBuilder("$");
        buf.append(toHexByte(num, 2));
        buf.append(toHexByte(lat, 8));
        buf.append(toHexByte(lon, 8));
        int tmp = settings.getCode(way.sel) & 0x0F;
        tmp |= (way.heading & 0x1FF) << 4;
        tmp |= way.avoidBarrels ? 0x8000 : 0;
        tmp |= way.jumpRamp ? 0x4000 : 0;
        tmp |= way.raiseFlag ? 0x2000 : 0;
        buf.append(toHexByte(tmp, 4));
        buf.append(toHexByte(check, 2));
        buf.append("\n\r");
        lines.add(buf.toString());
        num++;
      }
      // Send terminating line for Russ
      lines.add("!\n\r");
      return lines.toArray(new String[lines.size()]);
    }

    /*
     * GPSTileMap Utility Mehods
     */

    public static int toFixed (double val) {
      return (int) (val * 10000000.0);
    }

    private String toHexByte(int val, int digits) {
      StringBuilder buf = new StringBuilder();
      for (int ii = digits - 1; ii >= 0; ii--) {
        int digit = (val >> (ii * 4)) & 0x0F;
        check += digit;
        buf.append(hexVals[digit]);
      }
      return buf.toString();
    }

    /*
     *  Locations in GPSTileMap can be in three different formats:
     *
     *  Latitude/Longitude values expressed in decimal degrees as double values.
     *
     *  Google Static Maps "World" Coordinates expressed as double values where
     *  the X coordinate maps to Longitude and the Y to Lattitude.
     *  See: https://developers.google.com/maps/documentation/javascript/maptypes?hl=en
     *
     *  Screen Pixel Values expressed an int values, but scaled by the zoom factor
     *  currently selected for the map.
     *
     *  The following methods are used to convert between them.
     */

    private static double worldXToLon (double worldX) {
      return (worldX - originX) / pixelsPerLonDegree;
    }

    private static double worldYToLat (double worldY) {
      double latRadians = (worldY - originY) / -pixelsPerLonRadian;
      return GPSMap.radiansToDegrees(2.0 * Math.atan(Math.exp(latRadians)) - Math.PI / 2);
    }

    private static double lonToWorldX (double lon) {
      return originX + lon * pixelsPerLonDegree;
    }

    private static double latToWorldY (double lat) {
      double sinY = Math.sin(degreesToRadians(lat));
      return originY + 0.5 * Math.log((1.0 + sinY) / (1.0 - sinY)) * -pixelsPerLonRadian;
    }

    private static double degreesToRadians (double deg) {
      return deg * (Math.PI / 180.0);
    }

    private static double radiansToDegrees (double rad) {
      return rad / (Math.PI / 180.0);
    }

    private static Point lonLanToPixel (LonLat loc, int zoom) {
      double numTiles = 1 << zoom;
      return new Point((int) (lonToWorldX(loc.lon) * numTiles), (int) (latToWorldY(loc.lat) * numTiles));
    }

    private static LonLat pixelToLonLat (Point pLoc, int zoom) {
      double numTiles = 1 << zoom;
      return new LonLat(worldXToLon((double) pLoc.x / numTiles), worldYToLat((double) pLoc.y / numTiles));
    }

    private static Point.Double lonLatToWorld (LonLat loc) {
      return new Point.Double(lonToWorldX(loc.lon), latToWorldY(loc.lat));
    }

    private static LonLat worldToLonLat (Point.Double wLoc) {
      return new LonLat(worldXToLon(wLoc.x), worldYToLat(wLoc.y));
    }

    /**
     * Uses Haversine formula to calculate great circle distance between two points on a globe.
     * See: http://www.movable-type.co.uk/scripts/latlong.html
     * @param loc1 LonLat of point 1
     * @param loc2 LonLat of point 2
     * @return distance in kilometers
     */
    private static double distanceInKilometers (LonLat loc1, LonLat loc2) {
      // Haversine formula
      double φ1 = degreesToRadians(loc1.lat);
      double φ2 = degreesToRadians(loc2.lat);
      double Δφ = degreesToRadians(loc2.lat - loc1.lat);
      double Δλ = degreesToRadians(loc2.lon - loc1.lon);
      double a = Math.sin(Δφ/2) * Math.sin(Δφ/2) + Math.cos(φ1) * Math.cos(φ2) * Math.sin(Δλ/2) * Math.sin(Δλ/2);
      // Note: Radius of Earh in kilometers is 6371
      return 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a)) * 6371;
    }

    private static double distanceInFeet (LonLat loc1, LonLat loc2) {
      // Note: 1 kilometer is 3280.84 feet
      return distanceInKilometers(loc1, loc2) * 3280.84;
    }
  }

  public static void main (String[] args) {
    SwingUtilities.invokeLater(GPSTileMap::new);
  }

  private class Uploader {
    private ProgressMonitor   pbar;
    private Timer             timer;
    private int               idx = 0, startDelay = prefs.getInt("serial.delay", 2) * 2;

    private Uploader (final String[] lines, final JMenu menu, JTextField toolInfo) {
      try {
        jsscPort.openPort();
        waypointMenu.setEnabled(false);
        pbar = new ProgressMonitor(gpsMap, "Uploading...", null, 0, lines.length);
        pbar.setMillisToPopup(10);
        // Fire a timer every once in a while to update the progress.
        timer = new Timer(500, e -> {
          try {
            if (startDelay > 0) {
              startDelay--;
              pbar.setProgress(0);
            } else {
              if (idx >= lines.length) {
                pbar.close();
                timer.stop();
                jsscPort.closePort();
                menu.setEnabled(true);
                toolInfo.setText("Upload complete");
              } else {
                try {
                  jsscPort.sendString(lines[idx]);
                } catch (jssc.SerialPortException ex) {
                  pbar.close();
                  timer.stop();
                  jsscPort.closePort();
                  menu.setEnabled(true);
                  toolInfo.setText("Upload error");
                  return;
                }
                if (pbar.isCanceled()) {
                  pbar.close();
                  timer.stop();
                  jsscPort.closePort();
                  toolInfo.setText("Upload Cancelled");
                  menu.setEnabled(true);
                } else {
                  pbar.setProgress(++idx);
                }
              }
            }
          } catch (jssc.SerialPortException ex) {
            showErrorDialog("Error Sending to Serial Port " + jsscPort.getPortName());
          }
        });
        timer.start();
      } catch (jssc.SerialPortException ex) {
        showErrorDialog("Error Opening Serial Port " + jsscPort.getPortName());
      }
    }
  }

  public void run () {
    if (gpsMap.markSet.waypoints.size() < 1) {
      showErrorDialog("Must set at least one waypoint!");
      return;
    }
    try {
      GPSMap.MarkSet markSet = gpsMap.markSet;
      boolean nextWayPoint = false;
      while (simRun || markSet.simCar.speed > 0) {
        // Update waypoint positions (allows user to move Waypoints while sim runs)
        LonLat[] locWays = new LonLat[markSet.waypoints.size() + 1];
        locWays[0] = markSet.simCar.loc;
        int ii = 1;
        for (GPSTileMap.GPSMap.Waypoint wPnt : markSet.waypoints) {
          locWays[ii++] = wPnt.loc;
        }
        if (nextWayPoint && simRun) {
          // Check if we've reached last waypoint
          if (++markSet.simCar.wayIdx >= locWays.length) {
            simRun = false;
            markSet.simCar.wayIdx = locWays.length - 1;
          }
        }
        LonLat prevLoc = markSet.simCar.loc.copy();
        // Drive autonomous
        nextWayPoint = markSet.simCar.doMove(locWays[markSet.simCar.wayIdx], locWays[markSet.simCar.wayIdx - 1], simRun);
        LonLat nextLoc = markSet.simCar.loc.copy();
        // Calculate distance moved (in feet)
        double feet = GPSMap.distanceInFeet(prevLoc, nextLoc);
        gpsMap.toolInfo.setText("" + GPSMap.feetFmt.format(feet * 50) + " feet/sec");
        // Update screen and wait for next animation tick
        repaint();
        Thread.sleep(20);   // ~50 fps
      }
      runStop.setText("RUN");
      tabs.setEnabledAt(1, true);
    } catch (Exception ex) {
      ex.printStackTrace(System.out);
    }
  }

  public static class CarLink extends JPanel {
    CarLink () {
      // Add code here to communicate with car and configure settings
      // Possible parameters:
      //    Max Steering (angle)
      //    Steering Algorithm (may have subpanel for related options)
      //    Intercept Circle Radius
      //    Waypoint Turn Distance
      //    Odometry Calibration Value
      //    Compass Calibration Values (read only)
      //    Acceleration, Deceleration settings for SimCar
      //    IMU and Compass Realtime Readout

    }

    public void paint (Graphics g) {
      String text = "Coming Soon";
      Dimension win = getSize();
      Font font = new Font("Helevetica", Font.BOLD, 30);
      FontMetrics fm = g.getFontMetrics(font);
      java.awt.geom.Rectangle2D rect = fm.getStringBounds(text, g);
      int tWid = (int) rect.getWidth();
      int tHyt = (int) rect.getHeight();
      g.setFont(font);
      int dx = win.width / 2 - tWid / 2;
      int dy = win.height / 2 - tHyt / 2 + fm.getAscent();
      g.setColor(Color.lightGray);
      g.drawString(text, dx, dy);
    }
  }

  GPSTileMap () {
    super("GPS Tile Map");
    File uDir = new File(userDir);
    if (!uDir.exists()) {
      if (!uDir.mkdirs()) {
        throw new IllegalStateException("Unable to create directory " + userDir);
      }
    }
    //System.out.println("" + Math.abs(GPSMap.lonToWorldX(-0.5) - GPSMap.lonToWorldX(0.5)));
    //System.out.println("" + Math.abs(GPSMap.latToWorldY(-0.5) - GPSMap.latToWorldY(0.5)));
    setBackground(Color.white);
    setLayout(new BorderLayout(1, 1));
    MyToolBar toolBar = new MyToolBar();
    // Add Run/Stop Button to toolbar
    runStop = new JButton("RUN");
    runStop.setEnabled(false);
    runStop.setFont(new Font("Arial", Font.PLAIN, 20));
    runStop.setToolTipText("Run/Stop Simulation");
    runStop.addActionListener((ev) -> {
      if ("RUN".equals(runStop.getText())) {
        runStop.setText("STOP");
        simRun = true;
        tabs.setEnabledAt(1, false);
        (new Thread(this)).start();
      } else {
        runStop.setText("RUN");
        simRun = false;
        tabs.setEnabledAt(1, true);
      }
    });
    toolBar.add(runStop);
    // Add RESET Button to put car back at starting position
    JButton reset = new JButton("RESET");
    reset.setFont(new Font("Arial", Font.PLAIN, 20));
    reset.setToolTipText("Reset Car's Position to Start");
    reset.addActionListener((ev) -> {
      if (gpsMap.markSet.simCar != null) {
        gpsMap.markSet.simCar.reset();
        repaint();
      }
    });
    toolBar.add(reset);
    JPanel toolPanel = new JPanel(new BorderLayout());
    JPanel info = new JPanel();
    info.add(new JLabel("Info:"));
    JTextField toolInfo = new JTextField(25);
    toolInfo.setEditable(false);
    toolInfo.setText("---");
    info.add(toolInfo);
    JPanel topPane = new JPanel(new GridLayout(0,2));
    topPane.add(toolBar);
    topPane.add(info);
    toolPanel.add(topPane, BorderLayout.PAGE_START);
    toolPanel.add(gpsMap = new GPSMap(this, prefs, toolInfo), BorderLayout.CENTER);
    toolBar.registerGPSMap(gpsMap);
    String map = prefs.get("default.map", null);
    if (map != null) {
      // Load Map
      try {
        gpsMap.loadMap(map);
        toolInfo.setText("Declination: " + declinationFmt.format(gpsMap.mapSet.getDeclination()) + " degrees");
      } catch (Exception ex) {
        prefs.remove("default.map");
      }
    }
    tabs = new JTabbedPane();
    tabs.addTab("Map", toolPanel);
    tabs.addTab("CarLink", carLink = new CarLink());
    add("Center", tabs);
    tabs.addChangeListener(ev -> {
      JTabbedPane src = (JTabbedPane)ev.getSource();
      Component selected = src.getSelectedComponent();
      try {
        if (selected == toolPanel) {
          fileMenu.setVisible(true);
          zoomMenu.setVisible(true);
          optMenu.setVisible(true);
          waypointMenu.setVisible(true);
          settingsMenu.setVisible(true);
          bumpMenu.setVisible(true);
        } else {
          fileMenu.setVisible(false);
          zoomMenu.setVisible(false);
          optMenu.setVisible(false);
          waypointMenu.setVisible(false);
          settingsMenu.setVisible(false);
          bumpMenu.setVisible(false);
        }
      } catch (Exception ex) {
        ex.printStackTrace(System.out);
      }});
    // Add menu bar and menus
    menuBar = new JMenuBar();
    // Add File menu
    fileMenu = new JMenu("File");
    // Add Expert Mode Menu Item
    JCheckBoxMenuItem expert = new JCheckBoxMenuItem("Expert Mode", expertMode = prefs.getBoolean("expert.mode", false));
    expert.addActionListener(ev -> {
      boolean selected = ((AbstractButton) ev.getSource()).getModel().isSelected();
      expertMode = selected;
      prefs.putBoolean("expert.mode", selected);
      if (settingsMenu != null) {
        settingsMenu.setVisible(expertMode);
      }
    });
    fileMenu.add(expert);
    String[] args = new String[] {"Open Map", "Create Map", "Quit GPS Tile Map"};
    int[] keys = new int[]        {OPEN_KEY,   CREATE_KEY,   QUIT_KEY};
    menuBar.add(fileMenu);
    for (int ii = 0; ii < args.length; ii++) {
      String label = args[ii];
      JMenuItem mItem = new JMenuItem(label);
      if (keys.length >= ii && keys[ii] > 0) {
        if (osName.contains("windows")) {
          mItem.setAccelerator(KeyStroke.getKeyStroke(keys[ii], InputEvent.CTRL_MASK));
        } else if (osName.contains("mac")) {
          mItem.setAccelerator(KeyStroke.getKeyStroke(keys[ii], InputEvent.META_MASK));
        }
      }
      fileMenu.add(mItem);
      mItem.addActionListener(this);
    }
    // Setup "Zoom" scale menu
    zoomMenu = new JMenu("Zoom");
    for (int ii = GPSMap.BaseZoom; ii <= GPSMap.MaxZoom; ii++) {
      JRadioButtonMenuItem item = new JRadioButtonMenuItem("" + ii, ii == gpsMap.zoom);
      zoomMenu.add(item);
      zoomGroup.add(item);
      item.addItemListener(e -> {
        JRadioButtonMenuItem item1 = (JRadioButtonMenuItem) e.getItem();
        if (item1.isSelected()) {
          gpsMap.changeZoom(Integer.parseInt(item1.getText()));
          gpsMap.repaint();
        }
      });
    }
    menuBar.add(zoomMenu);
    //
    // Add Options menu
    //
    optMenu = new JMenu("Options");
    // Add Edit Map Key item
    JMenuItem editKey = new JMenuItem("Edit Map Key");
    editKey.addActionListener(ev -> {
      String key = JOptionPane.showInputDialog("Enter Map Key", prefs.get("mapkey", ""));
      if (key != null && !key.isEmpty()) {
        prefs.put("mapkey", key);
      }
    });
    optMenu.add(editKey);
    // Add Rotate Map item
    JCheckBoxMenuItem rotation = new JCheckBoxMenuItem("Rotate Map 180", prefs.getBoolean("rotate.on", false));
    rotation.addActionListener(ev -> {
      boolean selected = ((AbstractButton) ev.getSource()).getModel().isSelected();
      gpsMap.screenRotate = selected;
      gpsMap.repaint();
      prefs.putBoolean("rotate.on", selected);
    });
    optMenu.add(rotation);
    // Add Show Obstacles item
    JCheckBoxMenuItem markers = new JCheckBoxMenuItem("Show Markers", prefs.getBoolean("markers.on", false));
    markers.addActionListener(ev -> {
      boolean selected = ((AbstractButton) ev.getSource()).getModel().isSelected();
      gpsMap.showMarkers = selected;
      gpsMap.repaint();
      prefs.putBoolean("markers.on", selected);
    });
    optMenu.add(markers);
    // Add Move Obstacles item
    JCheckBoxMenuItem moveMarkers = new JCheckBoxMenuItem("Move Markers", prefs.getBoolean("move_markers.on", false));
    moveMarkers.addActionListener(ev -> {
      boolean selected = ((AbstractButton) ev.getSource()).getModel().isSelected();
      gpsMap.moveMarkers = selected;
      gpsMap.repaint();
      prefs.putBoolean("move_markers.on", selected);
    });
    optMenu.add(moveMarkers);
    // Add Show Waypoint Numbers item
    JCheckBoxMenuItem numbers = new JCheckBoxMenuItem("Show Waypoint Numbers", prefs.getBoolean("numbers.on", false));
    numbers.addActionListener(ev -> {
      boolean selected = ((AbstractButton) ev.getSource()).getModel().isSelected();
      gpsMap.showNumbers = selected;
      prefs.putBoolean("numbers.on", selected);
      gpsMap.repaint();
    });
    optMenu.add(numbers);
    // Add Show Waypoint Setting item
    JCheckBoxMenuItem setting = new JCheckBoxMenuItem("Show Waypoint Settings", prefs.getBoolean("settings.on", false));
    setting.addActionListener(ev -> {
      boolean selected = ((AbstractButton) ev.getSource()).getModel().isSelected();
      gpsMap.showSettings = selected;
      prefs.putBoolean("settings.on", selected);
      gpsMap.repaint();
    });
    optMenu.add(setting);
    // Add Show Waypoint Lines item
    JCheckBoxMenuItem wayLines = new JCheckBoxMenuItem("Show Waypoint Lines", prefs.getBoolean("waylines.on", false));
    wayLines.addActionListener(ev -> {
      boolean selected = ((AbstractButton) ev.getSource()).getModel().isSelected();
      gpsMap.showWayLines = selected;
      prefs.putBoolean("waylines.on", selected);
      gpsMap.repaint();
    });
    optMenu.add(wayLines);
    // Add Reset Obstacle Markers item
    JMenuItem resetObj = new JMenuItem("Reset Markers");
    resetObj.addActionListener(ev -> {
      if (showWarningDialog("Reloading Markers will reset any changes you have made.  OK?")) {
        gpsMap.markSet.resetMarkers();
        repaint();
      }
    });
    optMenu.add(resetObj);
    // Add Load Markers item
    JMenuItem loadObj = new JMenuItem("Load Markers");
    loadObj.addActionListener(ev -> {
      if (showWarningDialog("Loading Markers will discard current set.  OK?")) {
        fc.setSelectedFile(new File(prefs.get("default.dir", "/")));
        if (fc.showOpenDialog(gpsMap) == JFileChooser.APPROVE_OPTION) {
          try {
            File tFile = fc.getSelectedFile();
            String tmp = getFile(tFile);
            gpsMap.markSet.loadMarkers(tmp);
          } catch (IOException ex) {
            ex.printStackTrace(System.out);
          }
        }
        repaint();
      }
    });
    optMenu.add(loadObj);
    // Add Save Markers item
    JMenuItem saveObj = new JMenuItem("Save Markers");
    saveObj.addActionListener(ev -> {
      fc.setSelectedFile(new File(prefs.get("default.dir", "/")));
      if (fc.showSaveDialog(gpsMap) == JFileChooser.APPROVE_OPTION) {
        File sFile = fc.getSelectedFile();
        if (sFile.exists()) {
          if (showWarningDialog("Overwrite Existing file?")) {
            gpsMap.markSet.saveMarkers(sFile);
          }
        } else {
          gpsMap.markSet.saveMarkers(sFile);
        }
        prefs.put("default.dir", sFile.getAbsolutePath());
      }
    });
    optMenu.add(saveObj);
    menuBar.add(optMenu);
    jsscPort = new JSSCPort(prefs, "main");
    // Add Ports Menu
    menuBar.add(buildPortsMenu(this));
    //
    // Add Waypoints menu
    //
    waypointMenu = new JMenu("Waypoints");
    // Add Clear All Waypoints item
    JMenuItem mItem1 = new JMenuItem("Clear All Waypoints");
    waypointMenu.add(mItem1);
    mItem1.addActionListener(ev -> gpsMap.clearWaypoints());
    // Add Upload Waypoints to Car item
    JMenuItem mItem2 = new JMenuItem("Upload Waypoints to Car");
    waypointMenu.add(mItem2);
    mItem2.addActionListener(ev -> {
      String[] lines = gpsMap.getUploadData();
      new Uploader(lines, waypointMenu, toolInfo);
    });
    // Add Save Waypoints As item
    JMenuItem mItem3 = new JMenuItem("Save Waypoints As...");
    waypointMenu.add(mItem3);
    mItem3.addActionListener(ev -> {
      fc.setSelectedFile(new File(prefs.get("default.dir", "/")));
      if (fc.showSaveDialog(gpsMap) == JFileChooser.APPROVE_OPTION) {
        File sFile = fc.getSelectedFile();
        if (sFile.exists()) {
          if (showWarningDialog("Overwrite Existing file?")) {
            saveFile(sFile, gpsMap.markSet.getCsvCoords(gpsMap.settings));
          }
        } else {
          saveFile(sFile, gpsMap.markSet.getCsvCoords(gpsMap.settings));
        }
        prefs.put("default.dir", sFile.getAbsolutePath());
      }
    });
    // Add Load Waypoints item
    JMenuItem mItem4 = new JMenuItem("Load Waypoints");
    waypointMenu.add(mItem4);
    mItem4.addActionListener(ev -> {
      fc.setSelectedFile(new File(prefs.get("default.dir", "/")));
      if (fc.showOpenDialog(gpsMap) == JFileChooser.APPROVE_OPTION) {
        try {
          File tFile = fc.getSelectedFile();
          String tmp = getFile(tFile);
          gpsMap.markSet.loadWaypoints(tmp, gpsMap.settings);
          repaint();
        } catch (IOException ex) {
          ex.printStackTrace(System.out);
        }
      }
    });
    // Add Waypoints Report item
    JMenuItem mItem5 = new JMenuItem("Waypoints Report");
    waypointMenu.add(mItem5);
    mItem5.addActionListener(ev -> {
      JTextArea textArea = new JTextArea(12, 36);
      StringBuilder buf = new StringBuilder();
      GPSMap.Waypoint lastWay = null;
      int ii = 1;
      double total = 0;
      for (GPSMap.Waypoint way : gpsMap.markSet.waypoints) {
        if (lastWay != null) {
            double feet = GPSMap.distanceInFeet(lastWay.loc, way.loc);
            total += feet;
            buf.append("Distance from waypoint ").append(ii).append(" to waypoint ").append(ii + 1).
                append(" is ").append(GPSMap.feetFmt.format(feet)).append(" feet\n");
            ii++;
        }
        lastWay = way;
      }
      buf.append("total distance ").append(GPSMap.feetFmt.format(total)).append(" feet\n");
      textArea.setText(buf.toString());
      textArea.setEditable(false);
      JScrollPane scrollPane = new JScrollPane(textArea);
      JOptionPane.showMessageDialog(null, scrollPane);
    });
    // Add Show Waypoints item
    JMenuItem mItem6 = new JMenuItem("Show Waypoints");
    waypointMenu.add(mItem6);
    mItem6.addActionListener(ev -> {
      JTextArea textArea = new JTextArea(12, 22);
      textArea.setText(gpsMap.markSet.getCsvCoords(gpsMap.settings));
      textArea.setEditable(false);
      JScrollPane scrollPane = new JScrollPane(textArea);
      JOptionPane.showMessageDialog(gpsMap, scrollPane, "Current Waypoints", JOptionPane.PLAIN_MESSAGE);
    });
    menuBar.add(waypointMenu);
    //
    // Add Settings menu
    //
    settingsMenu = new JMenu("Settings");
    // Add Reset Settings item
    JMenuItem mItem7 = new JMenuItem("Reset Settings");
    settingsMenu.add(mItem7);
    mItem7.addActionListener(ev -> {
      if (showWarningDialog("Resetting Settings will clear all current waypoints.  OK?")) {
        gpsMap.resetSettings();
      }
    });
    // Add Edit Settings item
    JMenuItem mItem8 = new JMenuItem("Edit Settings");
    settingsMenu.add(mItem8);
    mItem8.addActionListener(ev -> {
      JTextArea textArea = new JTextArea(12, 22);
      textArea.setText(gpsMap.settings.getSettings());
      textArea.setEditable(true);
      JScrollPane scrollPane = new JScrollPane(textArea);
      int res = JOptionPane.showConfirmDialog(gpsMap, scrollPane, "Edit and click OK to Save",
                                              JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
      if (res == 0) {
        if (showWarningDialog("Saving Settings will clear all current waypoints.  OK?")) {
          gpsMap.saveSettings(textArea.getText());
        }
      }
    });
    // Add Default Settings submenu
    JMenu subMenu = new JMenu("Default Setting");
    ButtonGroup group = new ButtonGroup();
    for (String desc : gpsMap.settings.wayVals.keySet()) {
      JRadioButtonMenuItem item = new JRadioButtonMenuItem(desc, desc.equals(gpsMap.settings.getDefault()));
      item.addActionListener(ev -> {
        JMenuItem item1 = (JMenuItem) ev.getSource();
        gpsMap.settings.setDefault(item1.getText());
        gpsMap.persistSettings();
      });
      subMenu.add(item);
      group.add(item);
    }
    settingsMenu.add(subMenu);
    settingsMenu.setVisible(expertMode);
    menuBar.add(settingsMenu);
    //
    // Add Bump menu
    //
    bumpMenu = new JMenu("Bump");
    // Add Edit Settings item
    JMenuItem bump = new JMenuItem("Edit Bump Program");
    bumpMenu.add(bump);
    bump.addActionListener(ev -> {
      JTextArea textArea = new JTextArea(12, 22);
      textArea.setText(prefs.get("bump.code", ""));
      textArea.setEditable(true);
      JScrollPane scrollPane = new JScrollPane(textArea);
      int res = JOptionPane.showConfirmDialog(gpsMap, scrollPane, "Edit and click OK to Save",
                                              JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
      if (res == 0) {
        prefs.put("bump.code", textArea.getText().toUpperCase());
      }
    });
    // Add Upload Bump Code to Car item
    JMenuItem bumpLoad = new JMenuItem("Upload Bump Code to Car");
    bumpMenu.add(bumpLoad);
    bumpLoad.addActionListener(ev -> {
      String[] lines = compileBumpCode();
      new Uploader(lines, waypointMenu, toolInfo);
    });
    menuBar.add(bumpMenu);
    // Setup menubar
    setJMenuBar(menuBar);
    // Add window close handler
    addWindowListener(new WindowAdapter() {    
      public void windowClosing (WindowEvent ev) {
        System.exit(0);
      }
    });
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        if (jsscPort != null) {
          try {
            jsscPort.closePort();
          } catch (jssc.SerialPortException ex) {
            ex.printStackTrace(System.out);
          }
        }
        if (gpsMap.markSet != null) {
          gpsMap.markSet.save();
        }
        try {
          prefs.flush();
        } catch (BackingStoreException ex) {
          ex.printStackTrace(System.out);
        }
      }
    });
    // Track window resize/move events and save in prefs
    addComponentListener(new ComponentAdapter() {
      public void componentMoved (ComponentEvent ev)  {
        Rectangle bounds = ev.getComponent().getBounds();
        prefs.putInt("window.x", bounds.x);
        prefs.putInt("window.y", bounds.y);
      }
      public void componentResized (ComponentEvent ev)  {
        Rectangle bounds = ev.getComponent().getBounds();
        prefs.putInt("window.width", bounds.width);
        prefs.putInt("window.height", bounds.height);
      }
    });
    setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    setSize(prefs.getInt("window.width", 800), prefs.getInt("window.height", 800));
    setLocation(prefs.getInt("window.x", 10), prefs.getInt("window.y", 10));
    setVisible(true);
  }
  
  private static Map<String,Integer> bumpKeywords = new HashMap<>();

  static {
    bumpKeywords.put("STOP",    0x1000);
    bumpKeywords.put("TURN",    0x2000);
    bumpKeywords.put("FORWARD", 0x3000);
    bumpKeywords.put("REVERSE", 0x4000);
    bumpKeywords.put("WAIT",    0x5000);
    bumpKeywords.put("RESUME",  0x6000);
  }
  
  byte check;
  
  private String toHexByte(int val, int digits) {
    StringBuilder buf = new StringBuilder();
    for (int ii = digits - 1; ii >= 0; ii--) {
      int digit = (val >> (ii * 4)) & 0x0F;
      check += digit;
      buf.append(hexVals[digit]);
    }
    return buf.toString();
  }

  /*
   *  Bump Code
   *
   *   Verbs: STOP, WAIT <t>, REVERSE <t>, FORWARD <t>, TURN <d>, RESUME
   *
   *   Where <t> is time in seconds (down to 10ths) and <d> is integer degrees where 0 is forward,
   *   positive values turn to right and negative values turn to left (assumes driving forward)
   *
   *   Example:
   *     STOP
   *     WAIT 1
   *     STEER 0
   *     REVERSE 1
   *     TURN 10
   *     FORWARD 1
   *     STEER -10
   *     FORWARD 1
   *     STEER 0
   *     RESUME
   */

  private String[] compileBumpCode () {
    String code = prefs.get("bump.code", "");
    List<String> lines = new ArrayList<>();
    StringTokenizer tok = new StringTokenizer(code, "\n\r");
    int idx = 0;
    while (tok.hasMoreTokens()) {
      String line = tok.nextToken().trim();
      String[] parts = line.split(" ");
      if (parts.length > 0 && bumpKeywords.containsKey(parts[0].trim())) {
        int val = bumpKeywords.get(parts[0].trim());
        if (parts.length > 1) {
          double tVal = Double.parseDouble(parts[1].trim());
          val += (int) (tVal * 10) & 0xFFF;
        }
        check = 0;
        lines.add("<" + toHexByte(idx++, 2) + toHexByte(val, 4) + toHexByte(check, 2) + "\n\r");
      }
    }
    return lines.toArray(new String[lines.size()]);
  }
  
  private JMenu buildPortsMenu (GPSTileMap parent) {
    JMenu portMenu = jsscPort.getPortMenu("Port");
    // Add "Rescan Ports" menu item
    portMenu.addSeparator();
    JMenuItem mItem = new JMenuItem("Rescan Ports");
    portMenu.add(mItem);
    mItem.addActionListener(parent);
    // Add Set Serial Delay menu item
    JMenuItem delay = new JMenuItem("Set Serial Delay");
    portMenu.add(delay);
    delay.addActionListener(ev -> {
      String res = (String)JOptionPane.showInputDialog(
                  gpsMap, "Enter delay in seconds", "Serial Delay",
                  JOptionPane.PLAIN_MESSAGE, null, null, Integer.toString(prefs.getInt("serial.delay", 2)));
      if (res != null) {
        try {
          prefs.putInt("serial.delay", Integer.parseInt(res));
        } catch (Exception ex) {
          showErrorDialog("Invalid delay value: " + res);
        }
      }
    });
    return portMenu;
  }
  
  private static String getFile (File file) throws IOException {
   FileInputStream fis = new FileInputStream(file);
   byte[] data = new byte[fis.available()];
   if (fis.read(data) != data.length) {
     throw new FileSystemException("Insufficient data");
   }
   fis.close();
   return new String(data, "UTF8");
  }
  
  private static void saveFile (File file, String text) {
    try {
      FileOutputStream out = new FileOutputStream(file);
      out.write(text.getBytes("UTF8"));
      out.close();
    } catch (IOException ex) {
      ex.printStackTrace(System.out);
    }
  }

  public void actionPerformed (ActionEvent ev) {
    String cmd = ev.getActionCommand();
    try {
      if ("Quit GPS Tile Map".equals(cmd)) {
        System.exit(0);
      } else if ("Open Map".equals(cmd)) {
        File dir = new File(userDir + "/");
        String[] maps = dir.list((dir1, name) -> name.endsWith(".map"));
        for (int ii = 0; ii < maps.length; ii++) {
          maps[ii] = maps[ii].substring(0, maps[ii].length() - 4);
        }
        String map = (String) JOptionPane.showInputDialog( this, "Select Map to Load",
                              "Load Map",  JOptionPane.PLAIN_MESSAGE, null, maps, null);
        if (map != null) {
          gpsMap.saveMarkers();
          // Load Map
          try {
            gpsMap.loadMap(map);
            prefs.put("default.map", map);
          } catch (Exception ex) {
            ex.printStackTrace(System.out);
          }
        }
      } else if ("Create Map".equals(cmd)) {
        JTextField lat = new JTextField();
        JTextField lon = new JTextField();
        Object[][] message = {{"Latitude:", lat}, {"Longitude:", lon}, {"Map Name:"}};
        String name = JOptionPane.showInputDialog(null, message, "Add New", JOptionPane.QUESTION_MESSAGE);
        if (notEmpty(name)  &&  notEmpty(lat.getText())  &&  notEmpty(lon.getText())) {
          ProgressMonitor pbar = new ProgressMonitor(gpsMap, "Downloading Map Image...", null, 0, 100);
          pbar.setMillisToPopup(10);
          SwingWorker<GPSMap.MapSet, Integer> worker = new SwingWorker<GPSMap.MapSet, Integer>() {
            @Override
            protected GPSMap.MapSet doInBackground() throws Exception {
              int mapWidth = GPSMap.zoomLevels[2].width;
              int mapHeight = GPSMap.zoomLevels[2].height;
              int tileSize = GPSMap.imgTileSize;
              int zoom = GPSMap.MaxZoom;
              GPSMap.MapSet mapSet = new GPSMap.MapSet(name, new LonLat(toDouble(lon.getText()), toDouble(lat.getText())));
              // Build Fully Zoomed Map image from Google Static Maps image tiles (other built by scaling down this image)
              int totalTiles = (mapWidth / tileSize) * (mapHeight / tileSize);
              int tileCount = 0;
              List<BufferedImage> tmp = new ArrayList<>();
              for (int xx = 0; xx < mapWidth; xx += tileSize) {
                for (int yy = 0; yy < mapHeight; yy += tileSize) {
                  Point pLoc = new Point(mapSet.mapLoc[2].x + xx + 256 - mapWidth / 2, mapSet.mapLoc[2].y + yy + 256 - mapHeight / 2);
                  LonLat loc = GPSMap.pixelToLonLat(pLoc,  zoom);
                  try {
                    String url = "http://maps.googleapis.com/maps/api/staticmap?center=" +
                                  GPSMap.lonLatFmt.format(loc.lat) + "," + GPSMap.lonLatFmt.format(loc.lon) + "&zoom=" + (zoom) +
                                  "&size=512x512&sensor=false&maptype=satellite" + (mapKey != null ? "&key=" + mapKey : "");
                    tileCount++;
                    int percent = (int) (((double) tileCount / (double) totalTiles) * 99);
                    publish(percent);
                    tmp.add(ImageIO.read(new URL(url)));
                  } catch (Exception ex) {
                    ex.printStackTrace(System.out);
                  }
                }
              }
              BufferedImage[] mapImages = tmp.toArray(new BufferedImage[tmp.size()]);
              BufferedImage mapScr = new BufferedImage(mapWidth, mapHeight, BufferedImage.TYPE_INT_RGB);
              Graphics2D g1 = (Graphics2D) mapScr.getGraphics();
              int idx = 0;
              for (int xx = 0; xx < mapWidth; xx += tileSize) {
                for (int yy = 0; yy < mapHeight; yy += tileSize) {
                  if (idx < mapImages.length) {
                    g1.drawImage(mapImages[idx++], xx, yy, null);
                  }
                }
              }
              mapSet.maps[2] = mapScr;
              mapSet.saveMapSet();
              publish(100);
              return mapSet;
            }

            @Override
            // Can safely update the GUI from this method.
            protected void done() {
              try {
                GPSMap.MapSet mapSet = get();
                gpsMap.setMap(mapSet);
                gpsMap.saveMarkers();
                prefs.put("default.map", mapSet.name);
              } catch (Exception ex) {
                // This is thrown if we throw an exception from doInBackground.
                ex.printStackTrace(System.out);
              } finally {
                pbar.close();
              }
            }

            @Override
            // Can safely update the GUI from this method.
            protected void process(List<Integer> chunks) {
              if (pbar.isCanceled()) {
                cancel(true);
              }
              int percent = chunks.get(chunks.size()-1);
              pbar.setProgress(percent);
            }
          };
          worker.execute();
        } else {
          showErrorDialog("Must specify all parameters!");
        }
      } else if ("Rescan Ports".equals(cmd)) {
        menuBar.remove(3);
        menuBar.add(buildPortsMenu(this), 3);
        menuBar.validate();
      } else {
        System.out.println(cmd);
      }
    } catch (Exception ex) {
      ex.printStackTrace(System.out);
    }
  }
  
  private boolean notEmpty (String val) {
    return val != null  &&  val.length() > 0;
  }
  
 private static double toDouble (String val) {
    try {
      return Double.parseDouble(val);
    } catch (NumberFormatException ex) {
      return 0;
    }
  }
  
  private boolean showWarningDialog (String msg) {
    return JOptionPane.showConfirmDialog(this, msg, "Warning", JOptionPane.YES_NO_OPTION) == JOptionPane.OK_OPTION;
  }
  
  private void showErrorDialog (String msg) {
    JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.PLAIN_MESSAGE);
  }
}
