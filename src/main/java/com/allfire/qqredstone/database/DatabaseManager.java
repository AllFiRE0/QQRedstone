package com.allfire.qqredstone.database;

import com.allfire.qqredstone.QQRedstone;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.Material;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class DatabaseManager {
    
    private final QQRedstone plugin;
    private Connection connection;
    
    // Кеш в памяти
    private final Map<String, Mechanism> locationCache = new ConcurrentHashMap<>();
    private final Map<String, List<Mechanism>> frequencySendersCache = new ConcurrentHashMap<>();
    private final Map<String, List<Mechanism>> frequencyReceiversCache = new ConcurrentHashMap<>();
    
    public DatabaseManager(QQRedstone plugin) {
        this.plugin = plugin;
    }
    
    public void initialize() {
        try {
            Class.forName("org.sqlite.JDBC");
            String dbPath = "jdbc:sqlite:" + plugin.getDataFolder().getAbsolutePath() + "/mechanisms.db";
            connection = DriverManager.getConnection(dbPath);
            
            createTables();
            loadAllIntoCache();
            
            plugin.getLogger().info("SQLite инициализирован! Загружено механизмов: " + locationCache.size());
        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка инициализации SQLite: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void createTables() throws SQLException {
        String mechanismsTable = """
            CREATE TABLE IF NOT EXISTS mechanisms (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                frequency TEXT NOT NULL,
                owner_uuid TEXT NOT NULL,
                world TEXT NOT NULL,
                x INTEGER NOT NULL,
                y INTEGER NOT NULL,
                z INTEGER NOT NULL,
                attached_world TEXT NOT NULL,
                attached_x INTEGER NOT NULL,
                attached_y INTEGER NOT NULL,
                attached_z INTEGER NOT NULL,
                attached_type TEXT NOT NULL,
                attached_face TEXT,
                below_world TEXT,
                below_x INTEGER,
                below_y INTEGER,
                below_z INTEGER,
                below_type TEXT,
                book_power INTEGER DEFAULT 0,
                created_at INTEGER,
                updated_at INTEGER,
                UNIQUE(world, x, y, z)
            )
        """;
        
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(mechanismsTable);
        }
        
        // Индексы
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_frequency ON mechanisms(frequency)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_attached ON mechanisms(attached_world, attached_x, attached_y, attached_z)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_below ON mechanisms(below_world, below_x, below_y, below_z)");
        }
    }
    
    public void loadAllIntoCache() {
        locationCache.clear();
        frequencySendersCache.clear();
        frequencyReceiversCache.clear();
        
        String query = "SELECT * FROM mechanisms";
        
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            
            while (rs.next()) {
                Mechanism m = Mechanism.fromResultSet(rs);
                String locKey = m.getWorld() + "," + m.getX() + "," + m.getY() + "," + m.getZ();
                locationCache.put(locKey, m);
                
                if (m.getType().equals("sender")) {
                    frequencySendersCache.computeIfAbsent(m.getFrequency(), k -> new ArrayList<>()).add(m);
                } else {
                    frequencyReceiversCache.computeIfAbsent(m.getFrequency(), k -> new ArrayList<>()).add(m);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Ошибка загрузки кеша: " + e.getMessage());
        }
    }
    
    public void addMechanism(Mechanism mechanism) {
        String insert = """
            INSERT INTO mechanisms (
                type, frequency, owner_uuid, world, x, y, z,
                attached_world, attached_x, attached_y, attached_z, attached_type, attached_face,
                below_world, below_x, below_y, below_z, below_type, book_power,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        
        try (PreparedStatement pstmt = connection.prepareStatement(insert)) {
            long now = System.currentTimeMillis();
            pstmt.setString(1, mechanism.getType());
            pstmt.setString(2, mechanism.getFrequency());
            pstmt.setString(3, mechanism.getOwnerUuid());
            pstmt.setString(4, mechanism.getWorld());
            pstmt.setInt(5, mechanism.getX());
            pstmt.setInt(6, mechanism.getY());
            pstmt.setInt(7, mechanism.getZ());
            pstmt.setString(8, mechanism.getAttachedWorld());
            pstmt.setInt(9, mechanism.getAttachedX());
            pstmt.setInt(10, mechanism.getAttachedY());
            pstmt.setInt(11, mechanism.getAttachedZ());
            pstmt.setString(12, mechanism.getAttachedType());
            pstmt.setString(13, mechanism.getAttachedFace());
            pstmt.setString(14, mechanism.getBelowWorld());
            pstmt.setInt(15, mechanism.getBelowX());
            pstmt.setInt(16, mechanism.getBelowY());
            pstmt.setInt(17, mechanism.getBelowZ());
            pstmt.setString(18, mechanism.getBelowType());
            pstmt.setInt(19, mechanism.getBookPower());
            pstmt.setLong(20, now);
            pstmt.setLong(21, now);
            pstmt.executeUpdate();
            
            // Обновляем кеш
            String locKey = mechanism.getWorld() + "," + mechanism.getX() + "," + mechanism.getY() + "," + mechanism.getZ();
            locationCache.put(locKey, mechanism);
            
            if (mechanism.getType().equals("sender")) {
                frequencySendersCache.computeIfAbsent(mechanism.getFrequency(), k -> new ArrayList<>()).add(mechanism);
            } else {
                frequencyReceiversCache.computeIfAbsent(mechanism.getFrequency(), k -> new ArrayList<>()).add(mechanism);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Ошибка добавления механизма: " + e.getMessage());
        }
    }
    
    public void removeMechanism(String world, int x, int y, int z) {
        String delete = "DELETE FROM mechanisms WHERE world = ? AND x = ? AND y = ? AND z = ?";
        
        try (PreparedStatement pstmt = connection.prepareStatement(delete)) {
            pstmt.setString(1, world);
            pstmt.setInt(2, x);
            pstmt.setInt(3, y);
            pstmt.setInt(4, z);
            pstmt.executeUpdate();
            
            // Удаляем из кеша
            String locKey = world + "," + x + "," + y + "," + z;
            Mechanism removed = locationCache.remove(locKey);
            
            if (removed != null) {
                if (removed.getType().equals("sender")) {
                    List<Mechanism> senders = frequencySendersCache.get(removed.getFrequency());
                    if (senders != null) senders.remove(removed);
                } else {
                    List<Mechanism> receivers = frequencyReceiversCache.get(removed.getFrequency());
                    if (receivers != null) receivers.remove(removed);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Ошибка удаления механизма: " + e.getMessage());
        }
    }
    
    public Mechanism getMechanismAt(Block block) {
        String locKey = block.getWorld().getName() + "," + block.getX() + "," + block.getY() + "," + block.getZ();
        return locationCache.get(locKey);
    }
    
    public List<Mechanism> getReceiversForFrequency(String frequency) {
        return frequencyReceiversCache.getOrDefault(frequency, new ArrayList<>());
    }
    
    public List<Mechanism> getMechanismsAttachedTo(Block block) {
        List<Mechanism> result = new ArrayList<>();
        String locKey = block.getWorld().getName() + "," + block.getX() + "," + block.getY() + "," + block.getZ();
        
        for (Mechanism m : locationCache.values()) {
            String attachedKey = m.getAttachedWorld() + "," + m.getAttachedX() + "," + m.getAttachedY() + "," + m.getAttachedZ();
            if (attachedKey.equals(locKey)) {
                result.add(m);
            }
        }
        return result;
    }
    
    public List<Mechanism> getMechanismsBelow(Block block) {
        List<Mechanism> result = new ArrayList<>();
        String locKey = block.getWorld().getName() + "," + block.getX() + "," + block.getY() + "," + block.getZ();
        
        for (Mechanism m : locationCache.values()) {
            if (m.getBelowWorld() != null) {
                String belowKey = m.getBelowWorld() + "," + m.getBelowX() + "," + m.getBelowY() + "," + m.getBelowZ();
                if (belowKey.equals(locKey)) {
                    result.add(m);
                }
            }
        }
        return result;
    }
    
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
    
    public void reload() {
        loadAllIntoCache();
    }
    
    public boolean hasMechanismAt(Block block) {
        return getMechanismAt(block) != null;
    }
    
    public boolean isOwnedByOther(Block block, String ownerUuid, boolean hasAdminOverride) {
        Mechanism m = getMechanismAt(block);
        if (m == null) return false;
        if (hasAdminOverride) return false;
        return !m.getOwnerUuid().equals(ownerUuid);
    }
}
