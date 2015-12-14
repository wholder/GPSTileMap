import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.filechooser.FileFilter;
import java.awt.*;
import java.awt.event.*;
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
  *  AVC  40.071000, -105.229500 declination 8.82
  *  Erma 32.919083, -117.114692 declination 11.97
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

public class GPSTileMap extends JFrame implements ActionListener {
  private static char[]       hexVals = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'};
  private static int          OPEN_KEY  = KeyEvent.VK_O;
  private static int          CREATE_KEY  = KeyEvent.VK_C;
  private static int          QUIT_KEY  = KeyEvent.VK_Q;
  private static String       osName = System.getProperty("os.name").toLowerCase();
  private static String       userDir = System.getProperty("user.home") + "/Library/" + GPSTileMap.class.getName();
  private JMenuBar            menuBar;
  private GPSMap              gpsMap;
  private JMenu               fileMenu;
  private JMenu               zoomMenu;
  private JMenu               optMenu;
  private JMenu               coordMenu;
  private JMenu               settingsMenu;
  private JMenu               bumpMenu;
  private JFileChooser        fc = new JFileChooser();
  private ButtonGroup         zoomGroup = new ButtonGroup();
  private boolean             expertMode;
  private JSSCPort            jsscPort;
  private transient Preferences prefs = Preferences.userRoot().node(this.getClass().getName());
  private static String       mapKey;

  {
    // Clear out any old preferences so any stored objects can be regenerated
    if (!"2".equals(prefs.get("version", null))) {
      try {
        prefs.clear();
        prefs.put("version", "2");
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
  }

  public static class LatLon implements Serializable {
    private static final long serialVersionUID = 7686575451237322227L;
    double                    lat, lon;

    public LatLon (double lat, double lon) {
      this.lat = lat;
      this.lon = lon;
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

  private static class MyToolBar extends JToolBar implements ActionListener, ChangeListener {
    private ButtonGroup group = new ButtonGroup();
    private String      state;
    private GPSMap      gpsMap;

    public MyToolBar () {
      super("Still draggable");
      add(getButton("arrow",  "arrow.png",      "Move",         "Move Marker"));
      add(getButton("hand",   "hand.png",       "Drag",         "Drag Map", true));
      add(getButton("cross",  "crosshair.png",  "Crosshair",    "GPS Coords"));
      add(getButton("tape",   "tape.png",       "Tape",         "Measure Distancee"));
      add(getButton("pin",    "pin.png",        "Pin",          "Set Waypoint"));
      add(getButton("barrel", "barrel.png",     "Barrel",       "Place Barrel"));
      add(getButton("trash",  "trash.gif",      "Delete",       "Delete Waypoint"));
      add(getButton("gps",    "gpsRef.png",     "GPS",           "GPS Reference"));
/*
      add(getButton("eye",    "target.png",     "Bullseye",     "Bullseye"));
      add(getButton("cut",    "cut.gif",        "Cut",          "Cut"));
      add(getButton("b1",     "b1.gif",         "AltName",      "Tooltip"));
      add(getButton("b4",     "b4.gif",         "AltName",      "Tooltip"));
      add(getButton("b5",     "b5.gif",         "AltName",      "Tooltip"));
      add(getButton("b6",     "notes.gif",      "AltName",      "Tooltip"));
      add(getButton("b7",     "b7.gif",         "AltName",      "Tooltip"));
*/
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
    private static DecimalFormat  latLonFmt = new DecimalFormat("#.0000000");
    private static DecimalFormat  feetFmt = new DecimalFormat("#.##");
    private static final int      tileSize = 256, imgTileSize = 512;
    private static final double   pixelsPerLonDegree = tileSize / 360.0;
    private static final double   pixelsPerLonRadian = tileSize / (2.0 * Math.PI);
    private static final double   origin = tileSize / 2.0;
    private static final int      BaseZoom = 19;
    private static final int      MaxZoom = 21;
    private static Dimension[]    zoomLevels = {new Dimension(2048, 2048), new Dimension(4096, 4096), new Dimension(8192, 8192)};
    private MapSet                mapSet;
    private MarkSet               markSet;
    private transient Image       offScr;
    private Dimension             win, lastWin;
    private JTextField            toolInfo;
    private int                   sX, sY, pX, pY, offX, offY;
    private int                   zoom;
    private int                   check;
    private boolean               tapePressed, showMarkers, moveMarkers, showNumbers, showSettings, showWayLines;
    private LatLon                tapeLoc;
    private String                tool;
    private Drawable              selected;
    protected Settings            settings = new Settings();
    private transient Preferences prefs;
    private transient GPSTileMap gpsTileMap;

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
      private double              declination;
      private int[]               pixLat = new int[3], pixLon = new int[3], ulLat = new int[3], ulLon = new int[3];
      private transient Image[]   maps = new Image[3];
      private byte[]              imgData;

      public MapSet (String name, double lat, double lon, double declination) {
        this.name = name;
        this.declination = declination;
        // Setup offsets for different zoom levels
        for (int ii = 0; ii < 3; ii++) {
          pixLat[ii] = GPSMap.latToPixelY(lat, ii + BaseZoom);
          pixLon[ii] = GPSMap.lonToPixelX(lon, ii + BaseZoom);
          // Compute upper left corner of map
          ulLat[ii] = pixLat[ii] - zoomLevels[ii].width / 2;
          ulLon[ii] = pixLon[ii] - zoomLevels[ii].height / 2;
        }
      }

      public double getDeclination () {
        return declination;
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

      // Compress images in maps[] to PNG format temporarily saved to imgData[] then serialize to output stream
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

      private int getUlLat (int zoom) {
        return ulLat[zoom - BaseZoom];
      }

      private int getUlLon (int zoom) {
        return ulLon[zoom - BaseZoom];
      }
    }

    private static class MarkSet implements Serializable {
      private static final long serialVersionUID = 7686575450447322227L;
      private String            name;
      private List<Marker>      markers;
      private List<Waypoint>    waypoints;
      private GPSReference      gpsReference;

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
        markers.add(new Marker(MarkerType.CIRCLE, 40.0710390, -105.2299660, 23, Color.RED));      // Barrel 1
        markers.add(new Marker(MarkerType.CIRCLE, 40.0709820, -105.2299570, 23, Color.RED));      // Barrel 2
        markers.add(new Marker(MarkerType.CIRCLE, 40.0709009, -105.2299000, 23, Color.RED));      // Barrel 3
        markers.add(new Marker(MarkerType.CIRCLE, 40.0708050, -105.2298690, 23, Color.RED));      // Marker 4

        markers.add(new Marker(MarkerType.POLY,   40.0712589, -105.2300260, 12, Color.YELLOW));   // Cormer 1
        markers.add(new Marker(MarkerType.POLY,   40.0707559, -105.2297179, 12, Color.YELLOW));   // Cormer 2
        markers.add(new Marker(MarkerType.POLY,   40.0709769, -105.2291910, 12, Color.YELLOW));   // Cormer 3
        markers.add(new Marker(MarkerType.POLY,   40.0713319, -105.2294660, 12, Color.YELLOW));   // Cormer 4
        markers.add(new Marker());

        markers.add(new Marker(MarkerType.HOOP,   40.0708299, -105.2295309, 60, Color.GREEN, 60));// Hoop
        markers.add(new Marker(MarkerType.RECT,   40.0710810, -105.2291989, 45, Color.BLUE, 60)); // Ramp
        markers.add(new Marker(MarkerType.CIRCLE, 40.0713749, -105.2297889, 30, Color.WHITE));    // Start
      }

      public void loadMarkers (String data) {
        markers = new ArrayList<>();
        StringTokenizer tok = new StringTokenizer(data, "\n\r");
        while (tok.hasMoreTokens()) {
          String line = tok.nextToken();
          String[] items = line.split(",");
          if (items.length == 1) {
            markers.add(new Marker());
          } else if (items.length >= 5) {
            MarkerType type = MarkerType.valueOf(items[0].trim());
            double lat = Double.valueOf(items[1].trim());
            double lon = Double.valueOf(items[2].trim());
            int radius = Integer.valueOf(items[3].trim());
            Color color = colors.get(items[4].trim().toLowerCase());
            color = color != null ? color : Color.ORANGE;
            if (items.length == 6) {
              markers.add(new Marker(type, lat, lon, radius, color, Integer.valueOf(items[5].trim())));
            } else {
              markers.add(new Marker(type, lat, lon, radius, color));
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
            if (mrk.type != MarkerType.POLYEND) {
              pOut.print(",");
              pOut.print(mrk.latLon.lat);
              pOut.print(",");
              pOut.print(mrk.latLon.lon);
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
          buf.append(format(way.latLon.lat));
          buf.append(",");
          buf.append(format(way.latLon.lon));
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
          Waypoint newWay = new Waypoint(lat, lon, code);
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
      CIRCLE, RECT, HOOP, POLY, POLYEND, GPSREF
    }

    public static class Marker extends Drawable implements Serializable {
      private static final long  serialVersionUID = 7626575480447322227L;
      private MarkerType  type;
      private int         rotation;
      protected Color     color;
      private boolean     hasRotation;

      public Marker (MarkerType type, double lat, double lon, int diameter, Color color) {
        super(lat, lon, diameter);
        this.type = type;
        this.color = color;
      }

      public Marker (MarkerType type, double lat, double lon, int diameter, Color color, int rotation) {
        this(type, lat, lon, diameter, color);
        this.rotation = rotation;
        hasRotation = true;
      }

      public Marker () {
        super(0, 0, 0);
        type = MarkerType.POLYEND;
      }

      protected Object[] doDraw (GPSTileMap.GPSMap gpsMap, Graphics2D g2, Object[] ret) {
        // Note: convert diameter from inches to pixels using factor of 2.2463006662281613 inches/pixel at zoom == 21
        int dia = (int) ((double) diameter / 2.2463006662281613) / (22 - gpsMap.zoom);
        g2.setColor(color);
        Point loc = gpsMap.getMapLoc(latLon);
        g2.setStroke(new BasicStroke(1.0f));
        switch (type) {
          case CIRCLE: {
              g2.fillOval(loc.x - dia / 2, loc.y - dia / 2, dia, dia);
            }
            break;
          case RECT: {
              Graphics2D g2d = (Graphics2D)g2.create();
              g2d.rotate(Math.toRadians(rotation), loc.x, loc.y);
              g2d.fillRect(loc.x - dia / 2, loc.y - dia / 2, dia, dia);
            }
            break;
          case HOOP: {
              Graphics2D g2d = (Graphics2D)g2.create();
              g2d.rotate(Math.toRadians(rotation), loc.x, loc.y);
              g2d.fillRect(loc.x - dia / 2, loc.y - 1, dia, 3);
            }
            break;
          case POLY: {
            g2.fillOval(loc.x - dia / 2, loc.y - dia / 2, dia, dia);
            if (ret != null && ret.length == 2) {
              Point lp = (Point) ret[1];
              g2.drawLine(lp.x, lp.y, loc.x, loc.y);
              return new Object[] {color, lp, new Point(loc.x, loc.y)};
            } else if (ret != null && ret.length == 3) {
              Point fp = (Point) ret[1];
              Point lp = (Point) ret[2];
              g2.drawLine(lp.x, lp.y, loc.x, loc.y);
              return new Object[] {color, fp, new Point(loc.x, loc.y)};
            }
            return new Object[] {color, new Point(loc.x, loc.y)};
            }
          case POLYEND: {
            // Note: ends definition of polygon and draws closing segment
            if (ret != null && ret.length == 3) {
              g2.setColor((Color) ret[0]);
              Point lp1 = (Point) ret[1];
              Point lp2 = (Point) ret[2];
              g2.drawLine(lp1.x, lp1.y, lp2.x, lp2.y);
            }
            } break;
          case GPSREF: {
            // Diameter of icon does not scale with zoom
            dia = 20;
            int hDia = dia / 2;
            g2.drawLine(loc.x, loc.y - hDia, loc.x, loc.y - hDia / 2);
            g2.drawLine(loc.x, loc.y + hDia, loc.x, loc.y + hDia / 2);
            g2.drawLine(loc.x - hDia, loc.y, loc.x - hDia / 2, loc.y);
            g2.drawLine(loc.x + hDia, loc.y, loc.x + hDia / 2, loc.y);
            g2.setStroke(thick[21 - gpsMap.zoom]);
            g2.drawOval(loc.x - hDia, loc.y - hDia, dia, dia);
          } break;
        }
        return new Object[0];
      }
    }

    abstract public static class Drawable implements Serializable {
      private static final long serialVersionUID = 7586575480447322227L;
      protected static Stroke[] thick = {new BasicStroke(3.0f), new BasicStroke(2.0f), new BasicStroke(1.0f)};
      protected LatLon          latLon;
      protected int             diameter;

      public Drawable (double lat, double lon, int diameter) {
        latLon = new LatLon(lat, lon);
        this.diameter = diameter;
      }

      public boolean selects (GPSTileMap.GPSMap gpsMap, int x, int y, int zoom) {
        Point loc = gpsMap.getMapLoc(latLon);
        return (int) Math.sqrt(Math.pow((double) loc.x - x, 2) + Math.pow((double) loc.y - y, 2)) < diameter / (22 - zoom);
      }
    }

    public static class Waypoint extends Drawable implements Serializable {
      private static final long  serialVersionUID = 7686575480337322227L;
      protected String   sel;
      protected boolean  avoidBarrels, jumpRamp, raiseFlag;
      protected int      heading;

      public Waypoint (double lat, double lon, String sel) {
        super(lat, lon, 25);
        this.sel = sel;
      }

      protected Object[] doDraw (GPSTileMap.GPSMap gpsMap, Graphics2D g2, Waypoint way, int num, Object[] ret) {
        int dia = diameter / (22 - gpsMap.zoom);
        int hDia = dia / 2;
        g2.setColor(Color.WHITE);
        Point loc = gpsMap.getMapLoc(latLon);
        g2.drawLine(loc.x, loc.y - hDia, loc.x, loc.y - hDia / 2);
        g2.drawLine(loc.x, loc.y + hDia, loc.x, loc.y + hDia / 2);
        g2.drawLine(loc.x - hDia, loc.y, loc.x - hDia / 2, loc.y);
        g2.drawLine(loc.x + hDia, loc.y, loc.x + hDia / 2, loc.y);
        g2.setStroke(thick[21 - gpsMap.zoom]);
        g2.drawOval(loc.x - hDia, loc.y - hDia, dia, dia);
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
          g2.drawString(buf.toString(), loc.x + hDia + 1, loc.y - hDia + 1);
          g2.setColor(Color.WHITE);
          g2.drawString(buf.toString(), loc.x + hDia, loc.y - hDia);
        }
        if (gpsMap.showWayLines) {
          g2.setStroke(new BasicStroke(1.0f));
          if (ret != null && ret.length == 1) {
            Point lp = (Point) ret[0];
            g2.drawLine(lp.x, lp.y, loc.x, loc.y);
            return new Object[] {lp, new Point(loc.x, loc.y)};
          } else if (ret != null && ret.length == 2) {
            Point fp = (Point) ret[0];
            Point lp = (Point) ret[1];
            g2.drawLine(lp.x, lp.y, loc.x, loc.y);
            return new Object[] {fp, new Point(loc.x, loc.y)};
          }
          return new Object[] {new Point(loc.x, loc.y)};
        }
        return null;
      }
    }

    public static class GPSReference extends Marker implements Serializable {
      private static final long     serialVersionUID = 7686575480447322227L;
      private double  refLat, refLon;

      public GPSReference (double lat, double lon) {
        super(MarkerType.GPSREF, lat, lon, 20, Color.ORANGE);
      }

      public void setLoc (double refLat, double refLon) {
        this.refLat = refLat;
        this.refLon = refLon;
      }

      public int getDeltaLat () {
        return toFixed(refLat - latLon.lat);
      }

      public int getDeltaLon () {
        return toFixed(refLon - latLon.lon);
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

    public Drawable findMarker (int x, int y) {
      for (Drawable mrk : markSet.waypoints) {
        if (mrk.selects(this, x, y, zoom)) {
          return mrk;
        }
      }
      for (Drawable mrk : markSet.markers) {
        if (mrk.selects(this, x, y, zoom)) {
          return mrk;
        }
      }
      if (markSet.gpsReference != null &&  markSet.gpsReference.selects(this, x, y, zoom)) {
        return markSet.gpsReference;
      }
      return null;
    }

    private void setPosition (Drawable mrk, int x, int y) {
      mrk.latLon = getMapLatLon(x, y);
    }

    public void setTool (String tool) {
      this.tool = tool;
      if ("hand".equals(tool)) {
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      } else {
        setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
      }
    }

    private boolean notEmpty (String val) {
      return val != null  &&  val.length() > 0;
    }

    class MyMouseAdapter extends MouseAdapter  {
      public void mousePressed (MouseEvent event) {
        boolean shiftDown = event.isShiftDown();
        if ("arrow".equals(tool)  ||  ("pin".equals(tool) && shiftDown)  ||  ("gps".equals(tool) && shiftDown)) {
          if (shiftDown) {
            Drawable mrk = findMarker(event.getX(), event.getY());
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
                message = new Object[][] {{null, chk1}, {null, chk2},  {null, chk3}, {"Heading", opt}, {"Speed:"}};
              } else {
                message = new Object[][] {{"Speed:"}};
              }
              String ret = (String) JOptionPane.showInputDialog(null, message,  "Select Waypoint " + num + " Speed Option",
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
              String lonTxt = (String)JOptionPane.showInputDialog(null, message,
                                  "GPS Benchmark", JOptionPane.PLAIN_MESSAGE,
                                  null, null, Double.toString(markSet.gpsReference.refLon));
              String latTxt = lat.getText();
              if (notEmpty(latTxt)  &&  notEmpty(lonTxt)) {
                // Save coordinates to GPS Reference
                markSet.gpsReference.setLoc(toDouble(latTxt), toDouble(lonTxt));
                toolInfo.setText("dLat: " + format(markSet.gpsReference.refLat - markSet.gpsReference.latLon.lat) +
                               ", dLon: " + format(markSet.gpsReference.refLon - markSet.gpsReference.latLon.lon));
                repaint();
              } else if (lonTxt != null) {
                JOptionPane.showMessageDialog(null, "Must provide lat and lon values");
              }
            }
          } else {
            Drawable mrk = findMarker(event.getX(), event.getY());
            if (mrk instanceof Waypoint || (mrk instanceof Marker && moveMarkers) || mrk instanceof GPSReference) {
              selected = mrk;
            }
          }
        } else if (shiftDown || "hand".equals(tool)) {
          sX = event.getX();
          sY = event.getY();
          pX = offX;
          pY = offY;
        } else if ("cross".equals(tool)) {
          int my = event.getY();
          int mx = event.getX();
          if (mapSet != null) {
            LatLon loc = getMapLatLon(mx, my);
            toolInfo.setText(format(loc.lat) + ", " + format(loc.lon));
          } else {
            toolInfo.setText("Map not loaded");
          }
        } else if ("pin".equals(tool)) {
          int my = event.getY();
          int mx = event.getX();
          if (mapSet != null) {
            LatLon loc = getMapLatLon(mx, my);
            markSet.waypoints.add(new Waypoint(loc.lat, loc.lon, settings.getDefault()));
            toolInfo.setText(format(loc.lat) + ", " + format(loc.lon));
            repaint();
          } else {
            toolInfo.setText("Map not loaded");
          }
        } else if ("barrel".equals(tool)) {
          int my = event.getY();
          int mx = event.getX();
          if (mapSet != null) {
            LatLon loc = getMapLatLon(mx, my);
            markSet.markers.add(new Marker(MarkerType.CIRCLE, loc.lat, loc.lon, 23, Color.RED));
            toolInfo.setText(format(loc.lat) + ", " + format(loc.lon));
            repaint();
          } else {
            toolInfo.setText("Map not loaded");
          }
        } else if ("gps".equals(tool)) {
          int my = event.getY();
          int mx = event.getX();
          if (mapSet != null) {
            LatLon loc = getMapLatLon(mx, my);
            markSet.gpsReference = new GPSReference(loc.lat, loc.lon);
            repaint();
          } else {
            toolInfo.setText("Map not loaded");
          }
        } else if ("trash".equals(tool)) {
          if (mapSet != null) {
            Drawable mkr = findMarker(event.getX(), event.getY());
            if (mkr instanceof Waypoint) {
              markSet.waypoints.remove(mkr);
              repaint();
            } else if (mkr instanceof GPSReference) {
              markSet.gpsReference = null;
              repaint();
            } else if (mkr instanceof Marker) {
              markSet.markers.remove(mkr);
              repaint();
            } else {
              toolInfo.setText("Not found");
            }
          } else {
            toolInfo.setText("Map not loaded");
          }
        } else if ("tape".equals(tool)) {
          if (mapSet != null) {
            if (tapePressed) {
              LatLon loc = getMapLatLon(event.getY(), event.getX());
              double lat1 = GPSMap.degreesToRadians(loc.lat);
              double lon1 = GPSMap.degreesToRadians(loc.lon);
              double lat2 = GPSMap.degreesToRadians(tapeLoc.lat);
              double lon2 = GPSMap.degreesToRadians(tapeLoc.lon);
              // Note: Radius of Earh in kilometers is 6371
              double dist = Math.acos(Math.sin(lat2) * Math.sin(lat1) + Math.cos(lat2) * Math.cos(lat1) * Math.cos(lon1 - lon2)) * 6371;
              // Note: 1 kilometer is 3280.84 feet
              toolInfo.setText("Distance is " + feetFmt.format(dist * 3280.84) + " feet");
              tapePressed = false;
            } else {
              tapeLoc = getMapLatLon(event.getY(), event.getX());
              toolInfo.setText("Select 2nd point");
              tapePressed = true;
            }
          } else {
            toolInfo.setText("Map not loaded");
          }
        }
      }

      public void mouseReleased (MouseEvent event) {
        boolean shiftDown = event.isShiftDown();
        if ("arrow".equals(tool)) {
          if (!shiftDown && selected != null) {
            int x = event.getX();
            int y = event.getY();
            setPosition(selected, x, y);
            repaint();
            selected = null;
          }
        } else if (shiftDown || "hand".equals(tool)) {
          prefs.putInt("window.offX", offX);
          prefs.putInt("window.offY", offY);
        }
      }
    }

    class MyMouseMotionAdapter extends MouseMotionAdapter  {
      public void mouseDragged (MouseEvent event) {
        boolean shiftDown = event.isShiftDown();
        if ("arrow".equals(tool)) {
          if (!shiftDown && selected != null) {
            int x = event.getX();
            int y = event.getY();
            setPosition(selected, x, y);
            repaint();
          }
        } else if (shiftDown || "hand".equals(tool)) {
          win = getSize();
          int dX = sX - event.getX();
          int dY = sY - event.getY();
          int base = zoom - BaseZoom;
          offX = Math.max(0, Math.min(zoomLevels[base].width - win.width, pX + dX));
          offY = Math.max(0, Math.min(zoomLevels[base].height - win.height, pY + dY));
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
      int cx = offX + hWid;
      int cy = offY + hHyt;
      double dx = (double) cx / zoomLevels[oldIdx].width;
      double dy = (double) cy / zoomLevels[oldIdx].height;
      offX = (int) (dx * zoomLevels[newIdx].width) - hWid;
      offY = (int) (dy * zoomLevels[newIdx].height) - hHyt;
      offX = Math.max(0, Math.min(zoomLevels[newIdx].width - win.width, offX));
      offY = Math.max(0, Math.min(zoomLevels[newIdx].height - win.height, offY));
      this.zoom = newZoom;
      prefs.putInt("window.zoom", zoom);
      prefs.putInt("window.offX", offX);
      prefs.putInt("window.offY", offY);
    }

    protected Point getMapLoc (LatLon latLon) {
      int y = GPSMap.latToPixelY(latLon.lat, zoom) - (mapSet.getUlLat(zoom) + offY);
      int x = GPSMap.lonToPixelX(latLon.lon, zoom) - (mapSet.getUlLon(zoom) + offX);
      return new Point(x, y);
    }

    protected LatLon getMapLatLon (int mx, int my) {
      double lat = GPSMap.pixelYToLat(mapSet.getUlLat(zoom) + offY + my, zoom);
      double lon = GPSMap.pixelXToLon(mapSet.getUlLon(zoom) + offX + mx, zoom);
      return new LatLon(lat, lon);
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
      setTool("hand");
      markSet = MarkSet.load(mapSet.name);
      initSettiings();
      showMarkers = prefs.getBoolean("markers.on", true);
      moveMarkers = prefs.getBoolean("move_markers.on", false);
      showNumbers = prefs.getBoolean("numbers.on", false);
      showSettings = prefs.getBoolean("settings.on", false);
      showWayLines = prefs.getBoolean("waylines.on", true);
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
      g2.setBackground(getBackground());
      if (mapSet != null) {
        g2.setRenderingHints(new RenderingHints(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON));
        // drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2, ImageObserver observer);
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
        }
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
        buf.append(toHexByte(toFixed(markSet.gpsReference.latLon.lat), 8));
        buf.append(toHexByte(toFixed(markSet.gpsReference.latLon.lon), 8));
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
        int lat = toFixed(way.latLon.lat);
        int lon = toFixed(way.latLon.lon);
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

    private static String format (double val) {
      return latLonFmt.format(val);
    }

    private static double pixelXToLon (int pixelX, int zoom) {
      return worldXToLon(pixelXToWorldX(pixelX, zoom));
    }

    private static double pixelYToLat (int pixelY, int zoom) {
      return worldYToLat(pixelYToWorldY(pixelY, zoom));
    }

    private static double pixelXToWorldX (int pixelX, int zoom) {
      double numTiles = 1 << zoom;
      return pixelX / numTiles;
    }

    private static double pixelYToWorldY (int pixelY, int zoom) {
      double numTiles = 1 << zoom;
      return pixelY / numTiles;
    }

    private static double worldXToLon (double worldX) {
      return (worldX - origin) / pixelsPerLonDegree;
    }

    private static double worldYToLat (double worldY) {
      double latRadians = (worldY - origin) / -pixelsPerLonRadian;
      return GPSMap.radiansToDegrees(2.0 * Math.atan(Math.exp(latRadians)) - Math.PI / 2);
    }

    private static int lonToPixelX (double lon, int zoom) {
      double numTiles = 1 << zoom;
      return (int) (lonToWorldX(lon) * numTiles);
    }

    private static int latToPixelY (double lat, int zoom) {
      double numTiles = 1 << zoom;
      return (int) (latToWorldY(lat) * numTiles);
    }

    private static double lonToWorldX (double lon) {
      return origin + lon * pixelsPerLonDegree;
    }

    private static double latToWorldY (double lat) {
      double sinY = Math.sin(degreesToRadians(lat));
      return origin + 0.5 * Math.log((1.0 + sinY) / (1.0 - sinY)) * -pixelsPerLonRadian;
    }

    private static double degreesToRadians (double deg) {
      return deg * (Math.PI / 180.0);
    }

    private static double radiansToDegrees (double rad) {
      return rad / (Math.PI / 180.0);
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
        coordMenu.setEnabled(false);
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
            ex.printStackTrace(System.out);
          }
        });
        timer.start();
      } catch (jssc.SerialPortException ex) {
        ex.printStackTrace(System.out);
      }
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
    setBackground(Color.white);
    setLayout(new BorderLayout(1, 1));
    MyToolBar toolBar = new MyToolBar();
    JPanel toolPanel = new JPanel(new BorderLayout());
    JPanel info = new JPanel();
    info.add(new JLabel("Info:"));
    JTextField toolInfo = new JTextField(20);
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
      } catch (Exception ex) {
        prefs.remove("default.map");
      }
    }
    add("Center", toolPanel);
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
      if (key != null  && !key.isEmpty()) {
        prefs.put("mapkey", key);
      }
    });
    optMenu.add(editKey);
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
      if (showWarningDialog("Reloading Obstacles will reset any changes you have made.  OK?")) {
        gpsMap.markSet.resetMarkers();
        repaint();
      }
    });
    optMenu.add(resetObj);
    // Add Load Markers item
    JMenuItem loadObj = new JMenuItem("Load Markers");
    loadObj.addActionListener(ev -> {
      if (showWarningDialog("Loading Obstacles will discard current set.  OK?")) {
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
    coordMenu = new JMenu("Waypoints");
    // Add Clear All Waypoints item
    JMenuItem mItem1 = new JMenuItem("Clear All Waypoints");
    coordMenu.add(mItem1);
    mItem1.addActionListener(ev -> gpsMap.clearWaypoints());
    // Add Upload Waypoints to Car item
    JMenuItem mItem2 = new JMenuItem("Upload Waypoints to Car");
    coordMenu.add(mItem2);
    mItem2.addActionListener(ev -> {
      String[] lines = gpsMap.getUploadData();
      new Uploader(lines, coordMenu, toolInfo);
    });
    // Add Save Waypoints As item
    JMenuItem mItem3 = new JMenuItem("Save Waypoints As...");
    coordMenu.add(mItem3);
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
    coordMenu.add(mItem4);
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
    // Add Coord Report item
    JMenuItem mItem5 = new JMenuItem("Waypoints Report");
    coordMenu.add(mItem5);
    mItem5.addActionListener(ev -> {
      JTextArea textArea = new JTextArea(12, 36);
      StringBuilder buf = new StringBuilder();
      GPSMap.Waypoint lastWay = null;
      int ii = 1;
      double total = 0;
      for (GPSMap.Waypoint way : gpsMap.markSet.waypoints) {
        if (lastWay != null) {
            double lat1 = GPSMap.degreesToRadians(lastWay.latLon.lat);
            double lon1 = GPSMap.degreesToRadians(lastWay.latLon.lon);
            double lat2 = GPSMap.degreesToRadians(way.latLon.lat);
            double lon2 = GPSMap.degreesToRadians(way.latLon.lon);
            double dist = Math.acos(Math.sin(lat1) * Math.sin(lat2) + Math.cos(lat1) * Math.cos(lat2) * Math.cos(lon2 - lon1))* 6371;
            double feet = dist * 3280.8;
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
    coordMenu.add(mItem6);
    mItem6.addActionListener(ev -> {
      JTextArea textArea = new JTextArea(12, 22);
      textArea.setText(gpsMap.markSet.getCsvCoords(gpsMap.settings));
      textArea.setEditable(false);
      JScrollPane scrollPane = new JScrollPane(textArea);
      JOptionPane.showMessageDialog(gpsMap, scrollPane, "Current Waypoints", JOptionPane.PLAIN_MESSAGE);
    });
    menuBar.add(coordMenu);
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
      new Uploader(lines, coordMenu, toolInfo);
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
        gpsMap.markSet.save();
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
        JTextField dec = new JTextField();
        Object[][] message = {{"Latitude:", lat}, {"Longitude:", lon}, {"Declination:", dec}, {"Map Name:"}};
        String name = JOptionPane.showInputDialog(null, message, "Add New", JOptionPane.QUESTION_MESSAGE);
        if (notEmpty(name)  &&  notEmpty(lat.getText())  &&  notEmpty(lon.getText())  &&  notEmpty(dec.getText())) {
          ProgressMonitor pbar = new ProgressMonitor(gpsMap, "Downloading Map Image...", null, 0, 100);
          pbar.setMillisToPopup(10);
          SwingWorker<GPSMap.MapSet, Integer> worker = new SwingWorker<GPSMap.MapSet, Integer>() {
            @Override
            protected GPSMap.MapSet doInBackground() throws Exception {
              int mapWidth = GPSMap.zoomLevels[2].width;
              int mapHeight = GPSMap.zoomLevels[2].height;
              int tileSize = GPSMap.imgTileSize;
              int zoom = GPSMap.MaxZoom;
              GPSMap.MapSet mapSet = new GPSMap.MapSet(name, toDouble(lat.getText()), toDouble(lon.getText()), toDouble(dec.getText()));
              // Build Fully Zoomed Map image from Google Static Maps image tiles (other built by scaling down this image)
              int totalTiles = (mapWidth / tileSize) * (mapHeight / tileSize);
              int tileCount = 0;
              List<BufferedImage> tmp = new ArrayList<>();
              for (int xx = 0; xx < mapWidth; xx += tileSize) {
                for (int yy = 0; yy < mapHeight; yy += tileSize) {
                  double cLat = GPSMap.pixelYToLat(mapSet.pixLat[2] + yy + 256 - mapHeight / 2, zoom);
                  double cLon = GPSMap.pixelXToLon(mapSet.pixLon[2] + xx + 256 - mapWidth / 2, zoom);
                  try {
                    String url = "http://maps.googleapis.com/maps/api/staticmap?center=" +
                                  GPSMap.format(cLat) + "," + GPSMap.format(cLon) + "&zoom=" + (zoom) +
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
