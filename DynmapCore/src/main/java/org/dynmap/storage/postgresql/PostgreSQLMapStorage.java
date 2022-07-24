package org.dynmap.storage.postgresql;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.dynmap.DynmapCore;
import org.dynmap.DynmapWorld;
import org.dynmap.Log;
import org.dynmap.MapType;
import org.dynmap.WebAuthManager;
import org.dynmap.MapType.ImageVariant;
import org.dynmap.PlayerFaces.FaceType;
import org.dynmap.storage.MapStorage;
import org.dynmap.storage.MapStorageBaseTileEnumCB;
import org.dynmap.storage.MapStorageTile;
import org.dynmap.storage.MapStorageTileEnumCB;
import org.dynmap.storage.MapStorageTileSearchEndCB;
import org.dynmap.utils.BufferInputStream;
import org.dynmap.utils.BufferOutputStream;

public class PostgreSQLMapStorage extends MapStorage {
    private String connectionString;
    private String userid;
    private String password;
    private String database;
    private String hostname;
    private String prefix = "";
    private String flags;
    private String tableTiles;
    private String tableMaps;
    private String tableFaces;
    private String tableMarkerIcons;
    private String tableMarkerFiles;
    private String tableStandaloneFiles;
    private String tableSchemaVersion;

    private int port;
    private static final int POOLSIZE = 5;
    private Connection[] cpool = new Connection[POOLSIZE];
    private int cpoolCount = 0;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private HashMap<String, Integer> mapKey = new HashMap<String, Integer>();

    public class StorageTile extends MapStorageTile {
        private Integer mapkey;
        private String uri;
        protected StorageTile(DynmapWorld world, MapType map, int x, int y,
                int zoom, ImageVariant var) {
            super(world, map, x, y, zoom, var);
            
            mapkey = getMapKey(world, map, var);

            if (zoom > 0) {
                uri = map.getPrefix() + var.variantSuffix + "/"+ (x >> 5) + "_" + (y >> 5) + "/" + "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz".substring(0, zoom) + "_" + x + "_" + y + "." + map.getImageFormat().getFileExt();
            }
            else {
                uri = map.getPrefix() + var.variantSuffix + "/"+ (x >> 5) + "_" + (y >> 5) + "/" + x + "_" + y + "." + map.getImageFormat().getFileExt();
            }
        }

        @Override
        public boolean exists() {
            if (mapkey == null) return false;
            boolean rslt = false;
            Connection c = null;
            boolean err = false;
            try {
                c = getConnection();
                Statement stmt = c.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT HashCode FROM " + tableTiles + " WHERE MapID=" + mapkey + " AND x=" + x + " AND y=" + y + " AND zoom=" + zoom + ";");
                rslt = rs.next();
                rs.close();
                stmt.close();
            } catch (SQLException x) {
            	logSQLException("Tile exists error", x);
                err = true;
            } catch (StorageShutdownException x) {
            	err = true;
            } finally {
                releaseConnection(c, err);
            }
            return rslt;
        }

        @Override
        public boolean matchesHashCode(long hash) {
            if (mapkey == null) return false;
            boolean rslt = false;
            Connection c = null;
            boolean err = false;
            try {
                c = getConnection();
                Statement stmt = c.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT HashCode FROM " + tableTiles + " WHERE MapID=" + mapkey + " AND x=" + x + " AND y=" + y + " AND zoom=" + zoom + ";");
                if (rs.next()) {
                    long v = rs.getLong("HashCode");
                    rslt = (v == hash);
                }
                rs.close();
                stmt.close();
            } catch (SQLException x) {
            	logSQLException("Tile matches hash error", x);
                err = true;
            } catch (StorageShutdownException x) {
            	err = true;
            } finally {
                releaseConnection(c, err);
            }
            return rslt;
        }

        @Override
        public TileRead read() {
            if (mapkey == null) return null;
            TileRead rslt = null;
            Connection c = null;
            boolean err = false;
            try {
                c = getConnection();
                Statement stmt = c.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT HashCode,LastUpdate,Format,Image FROM " + tableTiles + " WHERE MapID=" + mapkey + " AND x=" + x + " AND y=" + y + " AND zoom=" + zoom + ";");
                if (rs.next()) {
                    rslt = new TileRead();
                    rslt.hashCode = rs.getLong("HashCode");
                    rslt.lastModified = rs.getLong("LastUpdate");
                    rslt.format = MapType.ImageEncoding.fromOrd(rs.getInt("Format"));
                    byte[] img = rs.getBytes("Image");
                    rslt.image = new BufferInputStream(img);
                }
                rs.close();
                stmt.close();
            } catch (SQLException x) {
            	logSQLException("Tile read error", x);
                err = true;
            } catch (StorageShutdownException x) {
            	err = true;
            } finally {
                releaseConnection(c, err);
            }
            return rslt;
        }

        @Override
        public boolean write(long hash, BufferOutputStream encImage, long timestamp) {
            if (mapkey == null) return false;
            Connection c = null;
            boolean err = false;
            boolean exists = exists();
            // If delete, and doesn't exist, quit
            if ((encImage == null) && (!exists)) return false;
            
            try {
                c = getConnection();
                PreparedStatement stmt;
                if (encImage == null) { // If delete
                    stmt = c.prepareStatement("DELETE FROM " + tableTiles + " WHERE MapID=? AND x=? and y=? AND zoom=?;");
                    stmt.setInt(1, mapkey);
                    stmt.setInt(2, x);
                    stmt.setInt(3, y);
                    stmt.setInt(4, zoom);
                }
                else if (exists) {
                    stmt = c.prepareStatement("UPDATE " + tableTiles + " SET HashCode=?, LastUpdate=?, Format=?, Image=? WHERE MapID=? AND x=? and y=? AND zoom=?;");
                    stmt.setLong(1, hash);
                    stmt.setLong(2, timestamp);
                    stmt.setInt(3, map.getImageFormat().getEncoding().ordinal());
                    stmt.setBinaryStream(4, new BufferInputStream(encImage.buf, encImage.len), encImage.len);
                    stmt.setInt(5, mapkey);
                    stmt.setInt(6, x);
                    stmt.setInt(7, y);
                    stmt.setInt(8, zoom);
                }
                else {
                    stmt = c.prepareStatement("INSERT INTO " + tableTiles + " (MapID,x,y,zoom,HashCode,LastUpdate,Format,Image) VALUES (?,?,?,?,?,?,?,?);");
                    stmt.setInt(1, mapkey);
                    stmt.setInt(2, x);
                    stmt.setInt(3, y);
                    stmt.setInt(4, zoom);
                    stmt.setLong(5, hash);
                    stmt.setLong(6, timestamp);
                    stmt.setInt(7, map.getImageFormat().getEncoding().ordinal());
                    stmt.setBinaryStream(8, new BufferInputStream(encImage.buf, encImage.len), encImage.len);
               }
                stmt.executeUpdate();
                stmt.close();
                // Signal update for zoom out
                if (zoom == 0) {
                    world.enqueueZoomOutUpdate(this);
                }
            } catch (SQLException x) {
            	logSQLException("Tile write error", x);
                err = true;
            } catch (StorageShutdownException x) {
            	err = true;
            } finally {
                releaseConnection(c, err);
            }
            return !err;
        }

        @Override
        public boolean getWriteLock() {
            return PostgreSQLMapStorage.this.getWriteLock(uri);
        }

        @Override
        public void releaseWriteLock() {
            PostgreSQLMapStorage.this.releaseWriteLock(uri);
        }

        @Override
        public boolean getReadLock(long timeout) {
            return PostgreSQLMapStorage.this.getReadLock(uri, timeout);
        }

        @Override
        public void releaseReadLock() {
            PostgreSQLMapStorage.this.releaseReadLock(uri);
        }

        @Override
        public void cleanup() {
        }

        @Override
        public String getURI() {
            return uri;
        }

        @Override
        public void enqueueZoomOutUpdate() {
            world.enqueueZoomOutUpdate(this);
        }

        @Override
        public MapStorageTile getZoomOutTile() {
            int xx, yy;
            int step = 1 << zoom;
            if(x >= 0)
                xx = x - (x % (2*step));
            else
                xx = x + (x % (2*step));
            yy = -y;
            if(yy >= 0)
                yy = yy - (yy % (2*step));
            else
                yy = yy + (yy % (2*step));
            yy = -yy;
            return new StorageTile(world, map, xx, yy, zoom+1, var);
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof StorageTile) {
                StorageTile st = (StorageTile) o;
                return uri.equals(st.uri);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return uri.hashCode();
        }
    }

    public PostgreSQLMapStorage(){
    }

    @Override
    public boolean init(DynmapCore core) {
        if (!super.init(core)) {
            return false;
        }
        database = core.configuration.getString("storage/database", "dynmap");
        hostname = core.configuration.getString("storage/hostname", "localhost");
        port = core.configuration.getInteger("storage/port", 5432);
        userid = core.configuration.getString("storage/userid", "dynmap");
        password = core.configuration.getString("storage/password", "dynmap");
        prefix = core.configuration.getString("storage/prefix", "");
        flags = core.configuration.getString("storage/flags", "?allowReconnect=true");
        tableTiles = prefix + "Tiles";
        tableMaps = prefix + "Maps";
        tableFaces = prefix + "Faces";
        tableMarkerIcons = prefix + "MarkerIcons";
        tableMarkerFiles = prefix + "MarkerFiles";
        tableStandaloneFiles = prefix + "StandaloneFiles";
        tableSchemaVersion = prefix + "SchemaVersion";
        
        connectionString = "jdbc:postgresql://" + hostname + ":" + port + "/" + database + flags;
        Log.info("Opening PostgreSQL database " + hostname + ":" + port + "/" + database + " as map store");
        try {
            Class.forName("org.dynmap.org.postgresql.Driver");	// Use shaded name for our bundled driver
            // Initialize/update tables, if needed
            if(!initializeTables()) {
                return false;
            }
        } catch (ClassNotFoundException cnfx) {
            Log.severe("PostgreSQL-JDBC classes not found - PostgreSQL data source not usable");
            return false; 
        }
        return writeConfigPHP(core);
    }
    private boolean writeConfigPHP(DynmapCore core) {
    	File cfgfile = new File(baseStandaloneDir, "PostgreSQL_config.php");
    	if (!core.isInternalWebServerDisabled) {	// If using internal server
    		cfgfile.delete();	// Zap file (in case we left junk from last time)
    		return true;
    	}
    	// During initial startup, this can happen before baseStandaloneDir is setup
    	if (!baseStandaloneDir.exists()) {
    		baseStandaloneDir.mkdirs();
    	}
        FileWriter fw = null;
        try {
            fw = new FileWriter(cfgfile);
            fw.write("<?php\n$dbname = \'");
            fw.write(WebAuthManager.esc(database));
            fw.write("\';\n");
            fw.write("$dbhost = \'");
            fw.write(WebAuthManager.esc(hostname));
            fw.write("\';\n");
            fw.write("$dbport = ");
            fw.write(Integer.toString(port));
            fw.write(";\n");
            fw.write("$dbuserid = \'");
            fw.write(WebAuthManager.esc(userid));
            fw.write("\';\n");
            fw.write("$dbpassword = \'");
            fw.write(WebAuthManager.esc(password));
            fw.write("\';\n");
            fw.write("$dbprefix = \'");
            fw.write(WebAuthManager.esc(prefix));
            fw.write("\';\n");
            fw.write("$loginenabled = ");
            fw.write(core.isLoginSupportEnabled()?"true;\n":"false;\n");
            fw.write("?>\n");
        } catch (IOException iox) {
            Log.severe("Error writing PostgreSQL_config.php", iox);
            return false; 
        } finally {
            if (fw != null) {
                try { fw.close(); } catch (IOException x) {}
            }
        }
        return true;
    }

    private int getSchemaVersion() {
        int ver = 0;
        boolean err = false;
        Connection c = null;
        try {
            c = getConnection();    // Get connection (create DB if needed)
            Statement stmt = c.createStatement();
            ResultSet rs = stmt.executeQuery( "SELECT level FROM " + tableSchemaVersion + ";");
            if (rs.next()) {
                ver = rs.getInt("level");
            }
            rs.close();
            stmt.close();
        } catch (SQLException x) {
            err = true;
        } catch (StorageShutdownException x) {
        	err = true;
        } finally {
            if (c != null) { releaseConnection(c, err); }
        }
        return ver;
    }

    private void doUpdate(Connection c, String sql) throws SQLException {
        Statement stmt = c.createStatement();
        stmt.executeUpdate(sql);
        stmt.close();
    }

    private void doLoadMaps() {
        Connection c = null;
        boolean err = false;
        
        mapKey.clear();
        // Read the maps table - cache results
        try {
            c = getConnection();
            Statement stmt = c.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT * from " + tableMaps + ";");
            while (rs.next()) {
                int key = rs.getInt("ID");
                String worldID = rs.getString("WorldID");
                String mapID = rs.getString("MapID");
                String variant = rs.getString("Variant");
                long serverid = rs.getLong("ServerID");
                if (serverid == serverID) { // One of ours
                    mapKey.put(worldID + ":" + mapID + ":" + variant, key);
                }
            }
            rs.close();
            stmt.close();
        } catch (SQLException x) {
        	logSQLException("Error loading map table", x);
            err = true;
        } catch (StorageShutdownException x) {
        	err = true;
        } finally {
            releaseConnection(c, err);
            c = null;
        }
    }

    private Integer getMapKey(DynmapWorld w, MapType mt, ImageVariant var) {
        String id = w.getName() + ":" + mt.getPrefix() + ":" + var.toString();
        synchronized(mapKey) {
            Integer k = mapKey.get(id);
            if (k == null) {    // No hit: new value so we need to add it to table
                Connection c = null;
                boolean err = false;
                try {
                    c = getConnection();
                    // Insert row
                    PreparedStatement stmt = c.prepareStatement("INSERT INTO " + tableMaps + " (WorldID,MapID,Variant,ServerID) VALUES (?, ?, ?, ?);");
                    stmt.setString(1, w.getName());
                    stmt.setString(2, mt.getPrefix());
                    stmt.setString(3, var.toString());
                    stmt.setLong(4, serverID);
                    stmt.executeUpdate();
                    stmt.close();
                    //  Query key assigned
                    stmt = c.prepareStatement("SELECT ID FROM " + tableMaps + " WHERE WorldID = ? AND MapID = ? AND Variant = ? AND ServerID = ?;");
                    stmt.setString(1, w.getName());
                    stmt.setString(2, mt.getPrefix());
                    stmt.setString(3, var.toString());
                    stmt.setLong(4, serverID);
                    ResultSet rs = stmt.executeQuery();
                    if (rs.next()) {
                        k = rs.getInt("ID");
                        mapKey.put(id, k);
                    }
                    rs.close();
                    stmt.close();
                } catch (SQLException x) {
                	logSQLException("Error updating Maps table", x);
                    err = true;
                } catch (StorageShutdownException x) {
                	err = true;
                } finally {
                    releaseConnection(c, err);
                }
            }

            return k;
        }
    }

    private boolean initializeTables() {
        Connection c = null;
        boolean err = false;
        int version = getSchemaVersion();   // Get the existing schema version for the DB (if any)
        // If new, add our tables
        if (version == 0) {
            try {
            	Log.info("Initializing database schema");
                c = getConnection();
                doUpdate(c, "CREATE TABLE " + tableMaps + " (ID SERIAL PRIMARY KEY, WorldID VARCHAR(64) NOT NULL, MapID VARCHAR(64) NOT NULL, Variant VARCHAR(16) NOT NULL, ServerID BIGINT NOT NULL DEFAULT 0)");
                doUpdate(c, "CREATE TABLE " + tableTiles + " (MapID INT NOT NULL, x INT NOT NULL, y INT NOT NULL, zoom INT NOT NULL, HashCode BIGINT NOT NULL, LastUpdate BIGINT NOT NULL, Format INT NOT NULL, Image BYTEA, PRIMARY KEY(MapID, x, y, zoom))");
                doUpdate(c, "CREATE TABLE " + tableFaces + " (PlayerName VARCHAR(64) NOT NULL, TypeID INT NOT NULL, Image BYTEA, PRIMARY KEY(PlayerName, TypeID))");
                doUpdate(c, "CREATE TABLE " + tableMarkerIcons + " (IconName VARCHAR(128) PRIMARY KEY NOT NULL, Image BYTEA)");
                doUpdate(c, "CREATE TABLE " + tableMarkerFiles + " (FileName VARCHAR(128) PRIMARY KEY NOT NULL, Content BYTEA)");
                doUpdate(c, "CREATE TABLE " + tableStandaloneFiles + " (FileName VARCHAR(128) NOT NULL, ServerID BIGINT NOT NULL DEFAULT 0, Content BYTEA, PRIMARY KEY (FileName, ServerID))");
                doUpdate(c, "CREATE INDEX " + tableMaps + "_idx ON " + tableMaps + "(WorldID, MapID, Variant, ServerID)");  
                doUpdate(c, "CREATE TABLE " + tableSchemaVersion + " (level INT PRIMARY KEY NOT NULL)");
                doUpdate(c, "INSERT INTO " + tableSchemaVersion + " (level) VALUES (4)");
                version = 4;	// initialzed to current schema
            } catch (SQLException x) {
            	logSQLException("Error creating tables", x);
                err = true;
                return false;
            } catch (StorageShutdownException x) {
            	err = true;
            	return false;
            } finally {
                releaseConnection(c, err);
                c = null;
            }
        }
        if (version == 1) {
            try {
            	Log.info("Updating database schema from version = " + version);
                c = getConnection();
                doUpdate(c, "CREATE TABLE " + tableStandaloneFiles + " (FileName VARCHAR(128) NOT NULL, ServerID BIGINT NOT NULL DEFAULT 0, Content TEXT, PRIMARY KEY (FileName, ServerID))");
                doUpdate(c, "ALTER TABLE " + tableMaps + " ADD COLUMN ServerID BIGINT NOT NULL DEFAULT 0 AFTER Variant");
                doUpdate(c, "UPDATE " + tableSchemaVersion + " SET level=2 WHERE level = 1;");
                version = 2;
            } catch (SQLException x) {
            	logSQLException("Error upgrading tables to version=2", x);
                err = true;
                return false;
            } catch (StorageShutdownException x) {
            	err = true;
            	return false;
            } finally {
                releaseConnection(c, err);
                c = null;
            }
        }
        if (version == 2) {
            try {
            	Log.info("Updating database schema from version = " + version);
                c = getConnection();
                doUpdate(c, "DELETE FROM " + tableStandaloneFiles + ";");
                doUpdate(c, "ALTER TABLE " + tableStandaloneFiles + " DROP COLUMN Content;");
                doUpdate(c, "ALTER TABLE " + tableStandaloneFiles + " ADD COLUMN Content BYTEA;");
                doUpdate(c, "UPDATE " + tableSchemaVersion + " SET level=3 WHERE level = 2;");
                version = 3;
            } catch (SQLException x) {
            	logSQLException("Error upgrading tables to version=3", x);
                err = true;
                return false;
            } catch (StorageShutdownException x) {
            	err = true;
            	return false;
            } finally {
                releaseConnection(c, err);
                c = null;
            }
        }
        if (version == 3) {
            try {
            	Log.info("Updating database schema from version = " + version);
                c = getConnection();
                doUpdate(c, "CREATE INDEX " + tableMaps + "_idx ON " + tableMaps + "(WorldID, MapID, Variant, ServerID)");  
                doUpdate(c, "UPDATE " + tableSchemaVersion + " SET level=4 WHERE level = 3;");
                version = 4;
            } catch (SQLException x) {
            	logSQLException("Error upgrading tables to version=4", x);
                err = true;
                return false;
            } catch (StorageShutdownException x) {
            	err = true;
            	return false;
            } finally {
                releaseConnection(c, err);
                c = null;
            }
        }
    	Log.info("Schema version = " + version);
        // Load maps table - cache results
        doLoadMaps();
        
        return true;
    }

    private Connection getConnection() throws SQLException, StorageShutdownException {
        Connection c = null;
        if (isShutdown) throw new StorageShutdownException();
        synchronized (cpool) {
            while (c == null) {
                for (int i = 0; i < cpool.length; i++) {    // See if available connection
                    if (cpool[i] != null) { // Found one
                        c = cpool[i];
                        cpool[i] = null;
                        break;
                    }
                }
                if (c == null) {
                    if (cpoolCount < POOLSIZE) {  // Still more we can have
                        c = DriverManager.getConnection(connectionString, userid, password);
                        configureConnection(c);
                        cpoolCount++;
                    }
                    else {
                        try {
                            cpool.wait();
                        } catch (InterruptedException e) {
                            throw new SQLException("Interruped");
                        }
                    }
                }
            }
        }
        return c;
    }
    private static Connection configureConnection(Connection conn) throws SQLException {
        return conn;
    }

    private void releaseConnection(Connection c, boolean err) {
        if (c == null) return;
        synchronized (cpool) {
            if (!err)  {  // Find slot to keep it in pool
                for (int i = 0; i < POOLSIZE; i++) {
                    if (cpool[i] == null) {
                        cpool[i] = c;
                        c = null; // Mark it recovered (no close needed
                        cpool.notifyAll();
                        break;
                    }
                }
            }
            if (c != null) {  // If broken, just toss it
                try { c.close(); } catch (SQLException x) {}
                cpoolCount--;   // And reduce count
                cpool.notifyAll();
            }
        }
    }

    @Override
    public MapStorageTile getTile(DynmapWorld world, MapType map, int x, int y,
            int zoom, ImageVariant var) {
        return new StorageTile(world, map, x, y, zoom, var);
    }

    @Override
    public MapStorageTile getTile(DynmapWorld world, String uri) {
        String[] suri = uri.split("/");
        if (suri.length < 2) return null;
        String mname = suri[0]; // Map URI - might include variant
        MapType mt = null;
        ImageVariant imgvar = null;
        // Find matching map type and image variant
        for (int mti = 0; (mt == null) && (mti < world.maps.size()); mti++) {
            MapType type = world.maps.get(mti);
            ImageVariant[] var = type.getVariants();
            for (int ivi = 0; (imgvar == null) && (ivi < var.length); ivi++) {
                if (mname.equals(type.getPrefix() + var[ivi].variantSuffix)) {
                    mt = type;
                    imgvar = var[ivi];
                }
            }
        }
        if (mt == null) {   // Not found?
            return null;
        }
        // Now, take the last section and parse out coordinates and zoom
        String fname = suri[suri.length-1];
        String[] coord = fname.split("[_\\.]");
        if (coord.length < 3) { // 3 or 4
            return null;
        }
        int zoom = 0;
        int x, y;
        try {
            if (coord[0].charAt(0) == 'z') {
                zoom = coord[0].length();
                x = Integer.parseInt(coord[1]);
                y = Integer.parseInt(coord[2]);
            }
            else {
                x = Integer.parseInt(coord[0]);
                y = Integer.parseInt(coord[1]);
            }
            return getTile(world, mt, x, y, zoom, imgvar);
        } catch (NumberFormatException nfx) {
            return null;
        }
    }

    @Override
    public void enumMapTiles(DynmapWorld world, MapType map,
            MapStorageTileEnumCB cb) {
        List<MapType> mtlist;

        if (map != null) {
            mtlist = Collections.singletonList(map);
        }
        else {  // Else, add all directories under world directory (for maps)
            mtlist = new ArrayList<MapType>(world.maps);
        }
        for (MapType mt : mtlist) {
            ImageVariant[] vars = mt.getVariants();
            for (ImageVariant var : vars) {
                processEnumMapTiles(world, mt, var, cb, null, null);
            }
        }
    }
    private void processEnumMapTiles(DynmapWorld world, MapType map, ImageVariant var, MapStorageTileEnumCB cb, MapStorageBaseTileEnumCB cbBase, MapStorageTileSearchEndCB cbEnd) {
        Connection c = null;
        boolean err = false;
        Integer mapkey = getMapKey(world, map, var);
        if (mapkey == null) {
            if(cbEnd != null)
                cbEnd.searchEnded();
            return;
        }
        try {
            c = getConnection();
            boolean done = false;
            int offset = 0;
            int limit = 100;            
            while (!done) {
	            // Query tiles for given mapkey
	            Statement stmt = c.createStatement();
	            ResultSet rs = stmt.executeQuery(String.format("SELECT x,y,zoom,Format FROM %s WHERE MapID=%d OFFSET %d LIMIT %d;", tableTiles, mapKey, offset, limit));
	            int cnt = 0;
	            while (rs.next()) {
	                StorageTile st = new StorageTile(world, map, rs.getInt("x"), rs.getInt("y"), rs.getInt("zoom"), var);
	                final MapType.ImageEncoding encoding = MapType.ImageEncoding.fromOrd(rs.getInt("Format"));
	                if(cb != null)
	                    cb.tileFound(st, encoding);
	                if(cbBase != null && st.zoom == 0)
	                    cbBase.tileFound(st, encoding);
	                st.cleanup();
	                cnt++;
	            }
	            rs.close();
	            stmt.close();
	            if (cnt < limit) done = true;
            	offset += cnt;
            }
            if(cbEnd != null)
                cbEnd.searchEnded();
        } catch (SQLException x) {
        	logSQLException("Tile enum error", x);
            err = true;
        } catch (StorageShutdownException x) {
        	err = true;
        } finally {
            releaseConnection(c, err);
        }
    }
    @Override
    public void enumMapBaseTiles(DynmapWorld world, MapType map, MapStorageBaseTileEnumCB cbBase, MapStorageTileSearchEndCB cbEnd) {
        List<MapType> mtlist;

        if (map != null) {
            mtlist = Collections.singletonList(map);
        }
        else {  // Else, add all directories under world directory (for maps)
            mtlist = new ArrayList<MapType>(world.maps);
        }
        for (MapType mt : mtlist) {
            ImageVariant[] vars = mt.getVariants();
            for (ImageVariant var : vars) {
                processEnumMapTiles(world, mt, var, null, cbBase, cbEnd);
            }
        }
    }

    @Override
    public void purgeMapTiles(DynmapWorld world, MapType map) {
        List<MapType> mtlist;

        if (map != null) {
            mtlist = Collections.singletonList(map);
        }
        else {  // Else, add all directories under world directory (for maps)
            mtlist = new ArrayList<MapType>(world.maps);
        }
        for (MapType mt : mtlist) {
            ImageVariant[] vars = mt.getVariants();
            for (ImageVariant var : vars) {
                processPurgeMapTiles(world, mt, var);
            }
        }
    }

    private void processPurgeMapTiles(DynmapWorld world, MapType map, ImageVariant var) {
        Connection c = null;
        boolean err = false;
        Integer mapkey = getMapKey(world, map, var);
        if (mapkey == null) return;
        try {
            c = getConnection();
            // Query tiles for given mapkey
            Statement stmt = c.createStatement();
            stmt.executeUpdate("DELETE FROM " + tableTiles + " WHERE MapID=" + mapkey + ";");
            stmt.close();
        } catch (SQLException x) {
        	logSQLException("Tile purge error", x);
            err = true;
        } catch (StorageShutdownException x) {
        	err = true;
        } finally {
            releaseConnection(c, err);
        }
    }

    @Override
    public boolean setPlayerFaceImage(String playername, FaceType facetype,
            BufferOutputStream encImage) {
        Connection c = null;
        boolean err = false;
        boolean exists = hasPlayerFaceImage(playername, facetype);
        // If delete, and doesn't exist, quit
        if ((encImage == null) && (!exists)) return false;
        
        try {
            c = getConnection();
            PreparedStatement stmt;
            if (encImage == null) { // If delete
                stmt = c.prepareStatement("DELETE FROM " + tableFaces + " WHERE PlayerName=? AND TypeIDx=?;");
                stmt.setString(1, playername);
                stmt.setInt(2, facetype.typeID);
            }
            else if (exists) {
                stmt = c.prepareStatement("UPDATE " + tableFaces + " SET Image=? WHERE PlayerName=? AND TypeID=?;");
                stmt.setBinaryStream(1, new BufferInputStream(encImage.buf, encImage.len), encImage.len);
                stmt.setString(2, playername);
                stmt.setInt(3, facetype.typeID);
            }
            else {
                stmt = c.prepareStatement("INSERT INTO " + tableFaces + " (PlayerName,TypeID,Image) VALUES (?,?,?);");
                stmt.setString(1, playername);
                stmt.setInt(2, facetype.typeID);
                stmt.setBinaryStream(3, new BufferInputStream(encImage.buf, encImage.len), encImage.len);
            }
            stmt.executeUpdate();
            stmt.close();
        } catch (SQLException x) {
        	logSQLException("Face write error", x);
            err = true;
        } catch (StorageShutdownException x) {
        	err = true;
        } finally {
            releaseConnection(c, err);
        }
        return !err;
    }

    @Override
    public BufferInputStream getPlayerFaceImage(String playername,
            FaceType facetype) {
        Connection c = null;
        boolean err = false;
        BufferInputStream image = null;
        try {
            c = getConnection();
            PreparedStatement stmt = c.prepareStatement("SELECT Image FROM " + tableFaces + " WHERE PlayerName=? AND TypeID=?;");
            stmt.setString(1, playername);
            stmt.setInt(2, facetype.typeID);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                byte[] img = rs.getBytes("Image");
                image = new BufferInputStream(img);
            }
            rs.close();
            stmt.close();
        } catch (SQLException x) {
        	logSQLException("Face read error", x);
            err = true;
        } catch (StorageShutdownException x) {
        	err = true;
        } finally {
            releaseConnection(c, err);
        }
        return image;
    }

    @Override
    public boolean hasPlayerFaceImage(String playername, FaceType facetype) {
        Connection c = null;
        boolean err = false;
        boolean exists = false;
        try {
            c = getConnection();
            PreparedStatement stmt = c.prepareStatement("SELECT TypeID FROM " + tableFaces + " WHERE PlayerName=? AND TypeID=?;");
            stmt.setString(1, playername);
            stmt.setInt(2, facetype.typeID);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                exists = true;
            }
            rs.close();
            stmt.close();
        } catch (SQLException x) {
        	logSQLException("Face exists error", x);
            err = true;
        } catch (StorageShutdownException x) {
        	err = true;
        } finally {
            releaseConnection(c, err);
        }
        return exists;
    }

    @Override
    public boolean setMarkerImage(String markerid, BufferOutputStream encImage) {
        Connection c = null;
        boolean err = false;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        
        try {
            c = getConnection();
            boolean exists = false;
            stmt = c.prepareStatement("SELECT IconName FROM " + tableMarkerIcons + " WHERE IconName=?;");
            stmt.setString(1, markerid);
            rs = stmt.executeQuery();
            if (rs.next()) {
                exists = true;
            }
            rs.close();
            rs = null;
            stmt.close();
            stmt = null;
            if (encImage == null) { // If delete
                // If delete, and doesn't exist, quit
                if (!exists) return false;
                stmt = c.prepareStatement("DELETE FROM " + tableMarkerIcons + " WHERE IconName=?;");
                stmt.setString(1, markerid);
                stmt.executeUpdate();
            }
            else if (exists) {
                stmt = c.prepareStatement("UPDATE " + tableMarkerIcons + " SET Image=? WHERE IconName=?;");
                stmt.setBinaryStream(1, new BufferInputStream(encImage.buf, encImage.len), encImage.len);
                stmt.setString(2, markerid);
            }
            else {
                stmt = c.prepareStatement("INSERT INTO " + tableMarkerIcons + " (IconName,Image) VALUES (?,?);");
                stmt.setString(1, markerid);
                stmt.setBinaryStream(2, new BufferInputStream(encImage.buf, encImage.len), encImage.len);
            }
            stmt.executeUpdate();
        } catch (SQLException x) {
        	logSQLException("Marker write error", x);
            err = true;
        } catch (StorageShutdownException x) {
        	err = true;
        } finally {
            if (rs != null) { try { rs.close(); } catch (SQLException sx) {} }
            if (stmt != null) { try { stmt.close(); } catch (SQLException sx) {} }
            releaseConnection(c, err);
        }
        return !err;
    }

    @Override
    public BufferInputStream getMarkerImage(String markerid) {
        Connection c = null;
        boolean err = false;
        BufferInputStream image = null;
        try {
            c = getConnection();
            PreparedStatement stmt = c.prepareStatement("SELECT Image FROM " + tableMarkerIcons + " WHERE IconName=?;");
            stmt.setString(1, markerid);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                byte[] img = rs.getBytes("Image");
                image = new BufferInputStream(img);
            }
            rs.close();
            stmt.close();
        } catch (SQLException x) {
        	logSQLException("Marker read error", x);
            err = true;
        } catch (StorageShutdownException x) {
        	err = true;
        } finally {
            releaseConnection(c, err);
        }
        return image;
    }

    @Override
    public boolean setMarkerFile(String world, String content) {
        Connection c = null;
        boolean err = false;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            c = getConnection();
            boolean exists = false;
            stmt = c.prepareStatement("SELECT FileName FROM " + tableMarkerFiles + " WHERE FileName=?;");
            stmt.setString(1, world);
            rs = stmt.executeQuery();
            if (rs.next()) {
                exists = true;
            }
            rs.close();
            rs = null;
            stmt.close();
            stmt = null;
            if (content == null) { // If delete
                // If delete, and doesn't exist, quit
                if (!exists) return false;
                stmt = c.prepareStatement("DELETE FROM " + tableMarkerFiles + " WHERE FileName=?;");
                stmt.setString(1, world);
                stmt.executeUpdate();
            }
            else if (exists) {
                stmt = c.prepareStatement("UPDATE " + tableMarkerFiles + " SET Content=? WHERE FileName=?;");
                stmt.setBytes(1, content.getBytes(UTF8));
                stmt.setString(2, world);
            }
            else {
                stmt = c.prepareStatement("INSERT INTO " + tableMarkerFiles + " (FileName,Content) VALUES (?,?);");
                stmt.setString(1, world);
                stmt.setBytes(2, content.getBytes(UTF8));
            }
            stmt.executeUpdate();
        } catch (SQLException x) {
        	logSQLException("Marker file write error", x);
            err = true;
        } catch (StorageShutdownException x) {
        	err = true;
        } finally {
            if (rs != null) { try { rs.close(); } catch (SQLException sx) {} }
            if (stmt != null) { try { stmt.close(); } catch (SQLException sx) {} }
            releaseConnection(c, err);
        }
        return !err;
    }

    @Override
    public String getMarkerFile(String world) {
        Connection c = null;
        boolean err = false;
        String content = null;
        try {
            c = getConnection();
            PreparedStatement stmt = c.prepareStatement("SELECT Content FROM " + tableMarkerFiles + " WHERE FileName=?;");
            stmt.setString(1, world);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                byte[] img = rs.getBytes("Content");
                content = new String(img, UTF8);
            }
            rs.close();
            stmt.close();
        } catch (SQLException x) {
        	logSQLException("Marker file read error", x);
            err = true;
        } catch (StorageShutdownException x) {
        	err = true;
        } finally {
            releaseConnection(c, err);
        }
        return content;
    }

    @Override
    // External web server only
    public String getMarkersURI(boolean login_enabled) {
        return "standalone/PostgreSQL_markers.php?marker=";
   }

    @Override
    // External web server only
    public String getTilesURI(boolean login_enabled) {
        return "standalone/PostgreSQL_tiles.php?tile=";
    }

    @Override
    // External web server only
    public String getConfigurationJSONURI(boolean login_enabled) {
        return "standalone/PostgreSQL_configuration.php"; // ?serverid={serverid}";
    }
    
    @Override
    // External web server only
    public String getUpdateJSONURI(boolean login_enabled) {
        return "standalone/PostgreSQL_update.php?world={world}&ts={timestamp}"; // &serverid={serverid}";
    }

    @Override
    // External web server only
    public String getSendMessageURI() {
        return "standalone/PostgreSQL_sendmessage.php";
    }

    @Override
    public BufferInputStream getStandaloneFile(String fileid) {
        Connection c = null;
        boolean err = false;
        BufferInputStream content = null;
        try {
            c = getConnection();
            PreparedStatement stmt = c.prepareStatement("SELECT Content FROM " + tableStandaloneFiles + " WHERE FileName=? AND ServerID=?;");
            stmt.setString(1, fileid);
            stmt.setLong(2, serverID);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                byte[] img = rs.getBytes("Content");
                content = new BufferInputStream(img);
            }
            rs.close();
            stmt.close();
        } catch (SQLException x) {
        	logSQLException("Standalone file read error", x);
            err = true;
        } catch (StorageShutdownException x) {
        	err = true;
        } finally {
            releaseConnection(c, err);
        }
        return content;
    }

    @Override
    public boolean setStandaloneFile(String fileid, BufferOutputStream content) {
        Connection c = null;
        boolean err = false;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            c = getConnection();
            boolean exists = false;
            stmt = c.prepareStatement("SELECT FileName FROM " + tableStandaloneFiles + " WHERE FileName=? AND ServerID=?;");
            stmt.setString(1, fileid);
            stmt.setLong(2, serverID);
            rs = stmt.executeQuery();
            if (rs.next()) {
                exists = true;
            }
            rs.close();
            rs = null;
            stmt.close();
            stmt = null;
            if (content == null) { // If delete
                // If delete, and doesn't exist, quit
                if (!exists) return true;
                stmt = c.prepareStatement("DELETE FROM " + tableStandaloneFiles + " WHERE FileName=? AND ServerID=?;");
                stmt.setString(1, fileid);
                stmt.setLong(2, serverID);
                stmt.executeUpdate();
            }
            else if (exists) {
                stmt = c.prepareStatement("UPDATE " + tableStandaloneFiles + " SET Content=? WHERE FileName=? AND ServerID=?;");
                stmt.setBinaryStream(1, new BufferInputStream(content.buf, content.len), content.len);
                stmt.setString(2, fileid);
                stmt.setLong(3, serverID);
            }
            else {
                stmt = c.prepareStatement("INSERT INTO " + tableStandaloneFiles + " (FileName,ServerID,Content) VALUES (?,?,?);");
                stmt.setString(1, fileid);
                stmt.setLong(2, serverID);
                stmt.setBinaryStream(3, new BufferInputStream(content.buf, content.len), content.len);
            }
            stmt.executeUpdate();
        } catch (SQLException x) {
        	logSQLException("Standalone file write error", x);
            err = true;
        } catch (StorageShutdownException x) {
        	err = true;
        } finally {
            if (rs != null) { try { rs.close(); } catch (SQLException sx) {} }
            if (stmt != null) { try { stmt.close(); } catch (SQLException sx) {} }
            releaseConnection(c, err);
        }
        return !err;
    }
    @Override
    public boolean wrapStandaloneJSON(boolean login_enabled) {
        return false;
    }
    @Override
    public boolean wrapStandalonePHP() {
        return false;
    }
    @Override
    // External web server only
    public String getStandaloneLoginURI() {
        return "standalone/PostgreSQL_login.php";
    }
    @Override
    // External web server only
    public String getStandaloneRegisterURI() {
        return "standalone/PostgreSQL_register.php";
    }
    @Override
    public void setLoginEnabled(DynmapCore core) {
        writeConfigPHP(core);
    }
}
