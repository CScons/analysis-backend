package com.conveyal.taui.grids;

import com.conveyal.r5.common.GeometryUtils;
import com.google.common.io.LittleEndianDataOutputStream;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.MultiPolygon;
import com.vividsolutions.jts.geom.Polygon;
import org.geotools.data.*;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

import static java.lang.Math.atan;
import static java.lang.Math.cos;
import static java.lang.Math.log;
import static java.lang.Math.sinh;
import static java.lang.Math.tan;

/**
 * Class that represents a grid in the spherical Mercator "projection" at a given zoom level.
 * This is actually a sub-grid of the full-world web mercator grid, with a specified width and height and offset from
 * the edges of the world.
 */
public class Grid {

    /** The web mercator zoom level for this grid. */
    public final int zoom;

    /* The following fields establish the position of this sub-grid within the full worldwide web mercator grid. */

    /**
     * The pixel number of the northernmost pixel in this grid (smallest y value in web Mercator,
     * because y increases from north to south in web Mercator).
     */
    public final int north;

    /**
     * The pixel number of the northernmost pixel in this grid (smallest y value in web Mercator,
     * because y increases from north to south in web Mercator).
     */
    public final int west;

    /** The width of the grid in web Mercator pixels. */
    public final int width;

    /** The height of the grid in web Mercator pixels. */
    public final int height;

    /** The data values for each pixel within this grid. */
    private final double[][] grid;

    /**
     * @param zoom web mercator zoom level for the grid.
     * @param north latitude in decimal degrees of the north edge of this grid.
     * @param east longitude in decimal degrees of the east edge of this grid.
     * @param south latitude in decimal degrees of the south edge of this grid.
     * @param west longitude in decimal degrees of the west edge of this grid.
     */
    public Grid (int zoom, double north, double east, double south, double west) {
        this.zoom = zoom;
        this.north = latToPixel(north, zoom);
        this.height = latToPixel(south, zoom) - this.north;
        this.west = lonToPixel(west, zoom);
        this.width = lonToPixel(east, zoom) - this.west;
        this.grid = new double[width][height];
    }

    /**
     * Do pycnoplactic mapping:
     * the value associated with the supplied polygon a polygon will be split out proportionately to
     * all the web Mercator pixels that intersect it.
     */
    public void rasterize (Geometry geometry, double value) {
        // TODO do we need to convert to a local coordinate system? I don't think so; although we scale differently
        // in the two dimensions, the scale factor applied to each geometry should be consistent.

        double area = geometry.getArea();
        if (area < 1e-12) {
            throw new IllegalArgumentException("Geometry is too small");
        }

        Envelope env = geometry.getEnvelopeInternal();
        for (int worldx = lonToPixel(env.getMinX(), zoom); worldx <= lonToPixel(env.getMaxX(), zoom); worldx++) {
            // NB web mercator Y is reversed relative to latitude
            for (int worldy = latToPixel(env.getMaxY(), zoom); worldy <= latToPixel(env.getMinY(), zoom); worldy++) {
                int x = worldx - west;
                int y = worldy - north;

                if (x < 0 || x >= width || y < 0 || y >= height) continue; // off the grid

                Geometry pixel = getPixelGeometry(x + west, y + north, zoom);
                Geometry intersection = pixel.intersection(geometry);
                double weight = intersection.getArea() / area;
                grid[x][y] += weight * value;
            }
        }
    }

    /**
     * Burn point data into the grid.
     */
    public void incrementPoint (double lat, double lon, double amount) {
        int worldx = lonToPixel(lon, zoom);
        int worldy = latToPixel(lat, zoom);
        int x = worldx - west;
        int y = worldy - north;
        if (x >= 0 && x < width && y >= 0 && y < height) {
            grid[x][y] += amount;
        } else {
            // Warn that an attempt was made to increment outside the grid
        }
    }

    /** Write this grid out in R5 binary grid format. */
    public void write (OutputStream outputStream) throws IOException {
        // Java's DataOutputStream only outputs big-endian format ("network byte order").
        // These grids will be read out of Javascript typed arrays which use the machine's native byte order.
        // On almost all current hardware this is little-endian. Guava saves us again.
        LittleEndianDataOutputStream out = new LittleEndianDataOutputStream(outputStream);
        // A header consisting of six 4-byte integers specifying the zoom level and bounds.
        out.writeInt(zoom);
        out.writeInt(west);
        out.writeInt(north);
        out.writeInt(width);
        out.writeInt(height);
        // The rest of the file is 32-bit integers in row-major order (x changes faster than y), delta-coded.
        for (int y = 0, prev = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int val = (int) Math.round(grid[x][y]);
                out.writeInt(val - prev);
                prev = val;
            }
        }
        out.close();
    }

    /** Write this grid out to a normalized grayscale image in PNG format. */
    public void writePng(OutputStream outputStream) throws IOException {
        // Find maximum pixel value to normalize brightness
        double maxPixel = 0;
        for (double[] row : grid) {
            for (double value : row) {
                if (value > maxPixel) {
                    maxPixel = value;
                }
            }
        }

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        byte[] imgPixels = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        int p = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double density = grid[x][y];
                imgPixels[p++] = (byte)(density * 255 / maxPixel);
            }
        }

        ImageIO.write(img, "png", outputStream);
    }

    /** Write this grid out as an ESRI Shapefile. */
    public void writeShapefile (String fileName, String fieldName) {
        SimpleFeatureTypeBuilder builder = new SimpleFeatureTypeBuilder();
        builder.setName("Mercator Grid");
        builder.setCRS(DefaultGeographicCRS.WGS84);
        builder.add("the_geom", Polygon.class);
        builder.add(fieldName, Double.class);
        final SimpleFeatureType gridCell = builder.buildFeatureType();
        try {
            FileDataStore store = FileDataStoreFinder.getDataStore(new File(fileName));
            store.createSchema(gridCell);
            Transaction transaction = new DefaultTransaction("Save Grid");
            FeatureWriter writer = store.getFeatureWriterAppend(transaction);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    try {
                        double value = grid[x][y];
                        if (value > 0) {
                            SimpleFeature feature = (SimpleFeature) writer.next();
                            Polygon pixelPolygon = getPixelGeometry(x + west, y + north, zoom);
                            feature.setDefaultGeometry(pixelPolygon);
                            feature.setAttribute(fieldName, value);
                            writer.write();
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            transaction.commit();
            writer.close();
            store.dispose();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /* functions below from http://wiki.openstreetmap.org/wiki/Slippy_map_tilenames#Mathematics */

    public static int lonToPixel (double lon, int zoom) {
        return (int) ((lon + 180) / 360 * Math.pow(2, zoom) * 256);
    }

    public static double pixelToLon (int pixel, int zoom) {
        return pixel / (Math.pow(2, zoom) * 256) * 360 - 180;
    }

    public static int latToPixel (double lat, int zoom) {
        double latRad = Math.toRadians(lat);
        return (int) ((1 - log(tan(latRad) + 1 / cos(latRad)) / Math.PI) * Math.pow(2, zoom - 1) * 256);
    }

    public static double pixelToLat (int pixel, int zoom) {
        return Math.toDegrees(atan(sinh(Math.PI - (pixel / 256d) / Math.pow(2, zoom) * 2 * Math.PI)));
    }

    /**
     * @param x absolute (world) x pixel number at the given zoom level.
     * @param y absolute (world) y pixel number at the given zoom level.
     * @return a JTS Polygon in WGS84 coordinates for the given absolute (world) pixel.
     */
    public static Polygon getPixelGeometry (int x, int y, int zoom) {
        double minLon = pixelToLon(x, zoom);
        double maxLon = pixelToLon(x + 1, zoom);
        // The y axis increases from north to south in web Mercator.
        double minLat = pixelToLat(y + 1, zoom);
        double maxLat = pixelToLat(y, zoom);
        return GeometryUtils.geometryFactory.createPolygon(new Coordinate[] {
                new Coordinate(minLon, minLat),
                new Coordinate(minLon, maxLat),
                new Coordinate(maxLon, maxLat),
                new Coordinate(maxLon, minLat),
                new Coordinate(minLon, minLat)
        });
    }
}
