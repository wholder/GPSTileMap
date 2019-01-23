<p align="center"><img src="/images/GPSTileMap%20Screenshot.png"></p>

# GPSTileMap

GPSTileMap is a Mapping and Mission Planning App for Small Autonomous Vehicles.  I originally wrote it as part of my entry for the Sparkfun Autonomous Vehicle Completition (AVC) in 2013 and 2014.  Using the various tools in the toolbar, I would draw a _route_ for my vehicle to follow by clicking and dragging the mouse to place a series of _waypoints_ on the map.  Other tools allowed me to draw boundary lines and to place special markers on the map that denote various obstacles present on the course.  My vehicle, [Johhny Five](https://sites.google.com/site/wayneholder/home/johnny-five---mark-iii), used GPS for navigation, but also had various sensors for obstacle avoidance.

Participating in the Sparkfun AVC is quite challenging, as there was only one short setup day to get and have everything dialed in, but the flexibility of the GPSTileMap made it easy for me to program and update the race route to keep up with changing conditions.  My daughter, Belle, and her cousin, Clarissa, also used the software with vehicles I helped them build.

## Important

GPSTileMap uses the Google Static Maps API as it's sourece of images, which means that you have to have a valid "key" from Google to use this API.  Use **Options->Edit Map Key** to enter this key.  See [Google Static Maps API](https://developers.google.com/maps/documentation/maps-static/intro) for more details on how to obtain this key.

## Credit and Thanks

This project would have been much harder and much less cool without help from the following open source projects, or freely available software.

- [Java Simple Serial Connector 2.8.0](https://github.com/scream3r/java-simple-serial-connector) - JSSC is used to communicate with the Arduino-based programmer
- [IntelliJ IDEA from JetBrains](https://www.jetbrains.com/idea/) (my favorite development environment for Java coding. Thanks JetBrains!)
- [TSAGeoMag.java](https://github.com/SignalK/signalk-core-java/blob/master/src/main/java/nz/co/fortytwo/signalk/util/TSAGeoMag.java) (originally geomag.c and oorted to Java 1.0.2 by Tim Walker) is used to compute magnetic declination for the map. 