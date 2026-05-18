package com.allfire.qqredstone.database;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.sql.ResultSet;
import java.sql.SQLException;

public class Mechanism {
    
    private long id;
    private String type;
    private String frequency;
    private String ownerUuid;
    private String world;
    private int x;
    private int y;
    private int z;
    
    // Блок к которому прикреплён (стена/пол/потолок)
    private String attachedWorld;
    private int attachedX;
    private int attachedY;
    private int attachedZ;
    private String attachedType;
    private String attachedFace;  // NORTH, SOUTH, EAST, WEST, UP, DOWN
    
    // Блок под нажимной плитой (если отличается)
    private String belowWorld;
    private int belowX;
    private int belowY;
    private int belowZ;
    private String belowType;
    
    private int bookPower;
    private long createdAt;
    private long updatedAt;
    
    public static Mechanism fromResultSet(ResultSet rs) throws SQLException {
        Mechanism m = new Mechanism();
        m.id = rs.getLong("id");
        m.type = rs.getString("type");
        m.frequency = rs.getString("frequency");
        m.ownerUuid = rs.getString("owner_uuid");
        m.world = rs.getString("world");
        m.x = rs.getInt("x");
        m.y = rs.getInt("y");
        m.z = rs.getInt("z");
        m.attachedWorld = rs.getString("attached_world");
        m.attachedX = rs.getInt("attached_x");
        m.attachedY = rs.getInt("attached_y");
        m.attachedZ = rs.getInt("attached_z");
        m.attachedType = rs.getString("attached_type");
        m.attachedFace = rs.getString("attached_face");
        m.belowWorld = rs.getString("below_world");
        m.belowX = rs.getInt("below_x");
        m.belowY = rs.getInt("below_y");
        m.belowZ = rs.getInt("below_z");
        m.belowType = rs.getString("below_type");
        m.bookPower = rs.getInt("book_power");
        m.createdAt = rs.getLong("created_at");
        m.updatedAt = rs.getLong("updated_at");
        return m;
    }
    
    // Геттеры
    public long getId() { return id; }
    public String getType() { return type; }
    public String getFrequency() { return frequency; }
    public String getOwnerUuid() { return ownerUuid; }
    public String getWorld() { return world; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
    public String getAttachedWorld() { return attachedWorld; }
    public int getAttachedX() { return attachedX; }
    public int getAttachedY() { return attachedY; }
    public int getAttachedZ() { return attachedZ; }
    public String getAttachedType() { return attachedType; }
    public String getAttachedFace() { return attachedFace; }
    public String getBelowWorld() { return belowWorld; }
    public int getBelowX() { return belowX; }
    public int getBelowY() { return belowY; }
    public int getBelowZ() { return belowZ; }
    public String getBelowType() { return belowType; }
    public int getBookPower() { return bookPower; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    
    // Сеттеры (для Builder)
    public void setType(String type) { this.type = type; }
    public void setFrequency(String frequency) { this.frequency = frequency; }
    public void setOwnerUuid(String ownerUuid) { this.ownerUuid = ownerUuid; }
    public void setWorld(String world) { this.world = world; }
    public void setX(int x) { this.x = x; }
    public void setY(int y) { this.y = y; }
    public void setZ(int z) { this.z = z; }
    public void setAttachedWorld(String attachedWorld) { this.attachedWorld = attachedWorld; }
    public void setAttachedX(int attachedX) { this.attachedX = attachedX; }
    public void setAttachedY(int attachedY) { this.attachedY = attachedY; }
    public void setAttachedZ(int attachedZ) { this.attachedZ = attachedZ; }
    public void setAttachedType(String attachedType) { this.attachedType = attachedType; }
    public void setAttachedFace(String attachedFace) { this.attachedFace = attachedFace; }
    public void setBelowWorld(String belowWorld) { this.belowWorld = belowWorld; }
    public void setBelowX(int belowX) { this.belowX = belowX; }
    public void setBelowY(int belowY) { this.belowY = belowY; }
    public void setBelowZ(int belowZ) { this.belowZ = belowZ; }
    public void setBelowType(String belowType) { this.belowType = belowType; }
    public void setBookPower(int bookPower) { this.bookPower = bookPower; }
    
    // Вспомогательный метод для получения BlockFace из строки
    public BlockFace getAttachedBlockFace() {
        if (attachedFace == null) return BlockFace.UP;
        try {
            return BlockFace.valueOf(attachedFace);
        } catch (IllegalArgumentException e) {
            return BlockFace.UP;
        }
    }
}
