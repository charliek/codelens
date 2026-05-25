package com.example.shop.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;

/**
 * Blocking JDBC path: reads stock levels straight from a {@link DataSource}
 * with raw JDBC. This is the "blocking" side of the blocking-vs-reactive xref
 * contrast — references javax.sql.DataSource and java.sql.* directly.
 */
@Service
public class InventoryService {

    private final DataSource dataSource;

    public InventoryService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public int stockLevel(long productId) {
        try (Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement("SELECT qty FROM inventory WHERE product_id = ?")) {
            statement.setLong(1, productId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt("qty") : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to read stock level", e);
        }
    }
}
