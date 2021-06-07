package bearmaps.proj2c.server.handler.impl;

import bearmaps.proj2c.AugmentedStreetMapGraph;
import bearmaps.proj2c.server.handler.APIRouteHandler;
import spark.Request;
import spark.Response;
import bearmaps.proj2c.utils.Constants;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static bearmaps.proj2c.utils.Constants.SEMANTIC_STREET_GRAPH;
import static bearmaps.proj2c.utils.Constants.ROUTE_LIST;

/**
 * Handles requests from the web browser for map images. These images
 * will be rastered into one large image to be displayed to the user.
 * @author rahul, Josh Hug, _________
 */
public class RasterAPIHandler extends APIRouteHandler<Map<String, Double>, Map<String, Object>> {

    /**
     * Each raster request to the server will have the following parameters
     * as keys in the params map accessible by,
     * i.e., params.get("ullat") inside RasterAPIHandler.processRequest(). <br>
     * ullat : upper left corner latitude, <br> ullon : upper left corner longitude, <br>
     * lrlat : lower right corner latitude,<br> lrlon : lower right corner longitude <br>
     * w : user viewport window width in pixels,<br> h : user viewport height in pixels.
     **/
    private static final String[] REQUIRED_RASTER_REQUEST_PARAMS = {"ullat", "ullon", "lrlat",
            "lrlon", "w", "h"};

    /**
     * The result of rastering must be a map containing all of the
     * fields listed in the comments for RasterAPIHandler.processRequest.
     **/
    private static final String[] REQUIRED_RASTER_RESULT_PARAMS = {"render_grid", "raster_ul_lon",
            "raster_ul_lat", "raster_lr_lon", "raster_lr_lat", "depth", "query_success"};


    @Override
    protected Map<String, Double> parseRequestParams(Request request) {
        return getRequestParams(request, REQUIRED_RASTER_REQUEST_PARAMS);
    }

    /**
     * Takes a user query and finds the grid of images that best matches the query. These
     * images will be combined into one big image (rastered) by the front end. <br>
     *
     *     The grid of images must obey the following properties, where image in the
     *     grid is referred to as a "tile".
     *     <ul>
     *         <li>The tiles collected must cover the most longitudinal distance per pixel
     *         (LonDPP) possible, while still covering less than or equal to the amount of
     *         longitudinal distance per pixel in the query box for the user viewport size. </li>
     *         <li>Contains all tiles that intersect the query bounding box that fulfill the
     *         above condition.</li>
     *         <li>The tiles must be arranged in-order to reconstruct the full image.</li>
     *     </ul>
     *
     * @param requestParams Map of the HTTP GET request's query parameters - the query box and
     *               the user viewport width and height.
     *
     * @param response : Not used by this function. You may ignore.
     * @return A map of results for the front end as specified: <br>
     * "render_grid"   : String[][], the files to display. <br>
     * "raster_ul_lon" : Number, the bounding upper left longitude of the rastered image. <br>
     * "raster_ul_lat" : Number, the bounding upper left latitude of the rastered image. <br>
     * "raster_lr_lon" : Number, the bounding lower right longitude of the rastered image. <br>
     * "raster_lr_lat" : Number, the bounding lower right latitude of the rastered image. <br>
     * "depth"         : Number, the depth of the nodes of the rastered image;
     *                    can also be interpreted as the length of the numbers in the image
     *                    string. <br>
     * "query_success" : Boolean, whether the query was able to successfully complete; don't
     *                    forget to set this to true on success! <br>
     */
    @Override
    public Map<String, Object> processRequest(Map<String, Double> requestParams, Response response) {
        //System.out.println("yo, wanna know the parameters given by the web browser? They are:");
        //System.out.println(requestParams);
        Map<String, Object> results = new HashMap<>();
        //System.out.println("Since you haven't implemented RasterAPIHandler.processRequest, nothing is displayed in "+ "your browser.");
        double cur_lrlon = requestParams.get("lrlon");
        double cur_ullon = requestParams.get("ullon");
        double cur_ullat = requestParams.get("ullat");
        double cur_lrlat = requestParams.get("lrlat");
        double cur_w = requestParams.get("w");
        double cur_h = requestParams.get("h");
        double cur_LonDPP = LonDPP_CAL(cur_lrlon, cur_ullon, cur_w);

        if(cur_lrlon<cur_ullon ||
                cur_lrlat>cur_ullat ||
                cur_ullon>Constants.ROOT_LRLON ||
                cur_lrlon<Constants.ROOT_ULLON ||
                cur_ullat<Constants.ROOT_LRLAT ||
                cur_lrlat>Constants.ROOT_ULLAT) {
            results = queryFail();
            return results;
        }


        int tar_depth = depth_CAL(cur_LonDPP);
        String[][] tar_grid = grid_CAL(cur_ullon,cur_ullat,cur_lrlon,cur_lrlat,tar_depth);
        Map<String, Double> bound_Map = bound_CAL(cur_ullon,cur_ullat,cur_lrlon,cur_lrlat,tar_depth);
        double tar_ul_lon = bound_Map.get("ul_lon");
        double tar_ul_lat = bound_Map.get("ul_lat");
        double tar_lr_lon = bound_Map.get("lr_lon");
        double tar_lr_lat = bound_Map.get("lr_lat");
        boolean tar_success = true;
        results.put("render_grid", tar_grid);
        results.put("raster_ul_lon", tar_ul_lon);
        results.put("raster_ul_lat", tar_ul_lat);
        results.put("raster_lr_lon", tar_lr_lon);
        results.put("raster_lr_lat", tar_lr_lat);
        results.put("depth", tar_depth);
        results.put("query_success", tar_success);
        return results;
    }

    private double LonDPP_CAL(double lrlon, double ullon, double w) {
        return (lrlon-ullon)/w;
    }

    private int depth_CAL(double LonDPP) {
        double max_LonDPP = (Constants.ROOT_LRLON-Constants.ROOT_ULLON)/Constants.TILE_SIZE;
        for(int depth=0;depth<8;depth++) {
            if(max_LonDPP/Math.pow(2,depth) <= LonDPP) {
                return depth;
            }
        }
        return 7;
    }

    private String[][] grid_CAL(double ullon, double ullat, double lrlon, double lrlat, int depth) {
        Map<String, Integer> ul_index = locate(ullon,ullat,depth);
        Map<String, Integer> lr_index = locate(lrlon,lrlat,depth);
        int x_lowBound = ul_index.get("x");
        int x_upBound = lr_index.get("x");
        int y_upBound = lr_index.get("y");
        int y_lowBound = ul_index.get("y");
        String[][] grid = new String[y_upBound-y_lowBound+1][x_upBound-x_lowBound+1];
        for(int i=y_lowBound;i<=y_upBound;i++) {
            for(int j=x_lowBound;j<=x_upBound;j++) {
                grid[i-y_lowBound][j-x_lowBound] = "d"+ depth +"_x"+ j +"_y"+ i +".png";
            }
        }
        return grid;
    }

    private Map<String, Integer> locate(double lon, double lat, int depth) {
        Map<String, Integer> location = new HashMap<>();
        double tile_w = (Constants.ROOT_LRLON-Constants.ROOT_ULLON)/(Math.pow(2,depth));
        int x_index = (int) ((lon-Constants.ROOT_ULLON)/tile_w);
        double tile_h = (Constants.ROOT_ULLAT-Constants.ROOT_LRLAT)/(Math.pow(2,depth));
        int y_index = (int) ((Constants.ROOT_ULLAT-lat)/tile_h);
        location.put("x",x_index);
        location.put("y",y_index);
        return location;
    }

    private Map<String, Double> bound_CAL(double ullon, double ullat, double lrlon, double lrlat, int depth) {
        Map<String, Integer> ul_index = locate(ullon,ullat,depth);
        Map<String, Integer> lr_index = locate(lrlon,lrlat,depth);
        int x_lowBound = ul_index.get("x");
        int x_upBound = lr_index.get("x");
        int y_upBound = lr_index.get("y");
        int y_lowBound = ul_index.get("y");
        double tile_w = (Constants.ROOT_LRLON-Constants.ROOT_ULLON)/(Math.pow(2,depth));
        double tile_h = (Constants.ROOT_ULLAT-Constants.ROOT_LRLAT)/(Math.pow(2,depth));
        double ul_lon = Constants.ROOT_ULLON+x_lowBound*tile_w;
        double lr_lon = Constants.ROOT_ULLON+(x_upBound+1)*tile_w;
        double ul_lat = Constants.ROOT_ULLAT-y_lowBound*tile_h;
        double lr_lat = Constants.ROOT_ULLAT-(y_upBound+1)*tile_h;
        Map<String, Double> bound = new HashMap<>();
        bound.put("ul_lon",ul_lon);
        bound.put("lr_lon",lr_lon);
        bound.put("ul_lat",ul_lat);
        bound.put("lr_lat",lr_lat);
        return bound;
    }


    @Override
    protected Object buildJsonResponse(Map<String, Object> result) {
        boolean rasterSuccess = validateRasteredImgParams(result);

        if (rasterSuccess) {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            writeImagesToOutputStream(result, os);
            String encodedImage = Base64.getEncoder().encodeToString(os.toByteArray());
            result.put("b64_encoded_image_data", encodedImage);
        }
        return super.buildJsonResponse(result);
    }

    private Map<String, Object> queryFail() {
        Map<String, Object> results = new HashMap<>();
        results.put("render_grid", null);
        results.put("raster_ul_lon", 0);
        results.put("raster_ul_lat", 0);
        results.put("raster_lr_lon", 0);
        results.put("raster_lr_lat", 0);
        results.put("depth", 0);
        results.put("query_success", false);
        return results;
    }

    /**
     * Validates that Rasterer has returned a result that can be rendered.
     * @param rip : Parameters provided by the rasterer
     */
    private boolean validateRasteredImgParams(Map<String, Object> rip) {
        for (String p : REQUIRED_RASTER_RESULT_PARAMS) {
            if (!rip.containsKey(p)) {
                System.out.println("Your rastering result is missing the " + p + " field.");
                return false;
            }
        }
        if (rip.containsKey("query_success")) {
            boolean success = (boolean) rip.get("query_success");
            if (!success) {
                System.out.println("query_success was reported as a failure");
                return false;
            }
        }
        return true;
    }

    /**
     * Writes the images corresponding to rasteredImgParams to the output stream.
     * In Spring 2016, students had to do this on their own, but in 2017,
     * we made this into provided code since it was just a bit too low level.
     */
    private  void writeImagesToOutputStream(Map<String, Object> rasteredImageParams,
                                                  ByteArrayOutputStream os) {
        String[][] renderGrid = (String[][]) rasteredImageParams.get("render_grid");
        int numVertTiles = renderGrid.length;
        int numHorizTiles = renderGrid[0].length;

        BufferedImage img = new BufferedImage(numHorizTiles * Constants.TILE_SIZE,
                numVertTiles * Constants.TILE_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics graphic = img.getGraphics();
        int x = 0, y = 0;

        for (int r = 0; r < numVertTiles; r += 1) {
            for (int c = 0; c < numHorizTiles; c += 1) {
                graphic.drawImage(getImage(Constants.IMG_ROOT + renderGrid[r][c]), x, y, null);
                x += Constants.TILE_SIZE;
                if (x >= img.getWidth()) {
                    x = 0;
                    y += Constants.TILE_SIZE;
                }
            }
        }

        /* If there is a route, draw it. */
        double ullon = (double) rasteredImageParams.get("raster_ul_lon"); //tiles.get(0).ulp;
        double ullat = (double) rasteredImageParams.get("raster_ul_lat"); //tiles.get(0).ulp;
        double lrlon = (double) rasteredImageParams.get("raster_lr_lon"); //tiles.get(0).ulp;
        double lrlat = (double) rasteredImageParams.get("raster_lr_lat"); //tiles.get(0).ulp;

        final double wdpp = (lrlon - ullon) / img.getWidth();
        final double hdpp = (ullat - lrlat) / img.getHeight();
        AugmentedStreetMapGraph graph = SEMANTIC_STREET_GRAPH;
        List<Long> route = ROUTE_LIST;

        if (route != null && !route.isEmpty()) {
            Graphics2D g2d = (Graphics2D) graphic;
            g2d.setColor(Constants.ROUTE_STROKE_COLOR);
            g2d.setStroke(new BasicStroke(Constants.ROUTE_STROKE_WIDTH_PX,
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            route.stream().reduce((v, w) -> {
                g2d.drawLine((int) ((graph.lon(v) - ullon) * (1 / wdpp)),
                        (int) ((ullat - graph.lat(v)) * (1 / hdpp)),
                        (int) ((graph.lon(w) - ullon) * (1 / wdpp)),
                        (int) ((ullat - graph.lat(w)) * (1 / hdpp)));
                return w;
            });
        }

        rasteredImageParams.put("raster_width", img.getWidth());
        rasteredImageParams.put("raster_height", img.getHeight());

        try {
            ImageIO.write(img, "png", os);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private BufferedImage getImage(String imgPath) {
        BufferedImage tileImg = null;
        if (tileImg == null) {
            try {
                File in = new File(imgPath);
                tileImg = ImageIO.read(in);
                //tileImg = ImageIO.read(Thread.currentThread().getContextClassLoader().getResource(imgPath));
            } catch (IOException | NullPointerException e) {
                e.printStackTrace();
            }
        }
        return tileImg;
    }
}
