package com.adpilot.modules.advertising.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.adpilot.modules.advertising.support.ObjectStatusValueRow;

/**
 * Generic, column-parameterized access used by the Object_Status vocabulary migration
 * (Requirement 16.7) to read and rewrite one status column at a time.
 *
 * <p>The {@code table} and {@code column} names are interpolated with {@code ${...}} because MyBatis
 * cannot bind an identifier as a positional parameter. This is safe here: both names originate
 * exclusively from the fixed, compile-time {@code ObjectStatusColumnRegistry} and never from user
 * input, so there is no SQL-injection surface. The actual data <em>value</em> is always bound with
 * {@code #{}}.</p>
 */
@Mapper
public interface ObjectStatusColumnMigrationMapper {

    /**
     * Read every non-null {@code (id, value)} pair from one Object_Status column.
     *
     * @param table  the table name (from the registry)
     * @param column the status column name (from the registry)
     * @return the rows whose status value is non-null, in primary-key order
     */
    @Select("<script>"
            + "SELECT id AS id, ${column} AS value "
            + "FROM ${table} "
            + "WHERE ${column} IS NOT NULL "
            + "ORDER BY id"
            + "</script>")
    List<ObjectStatusValueRow> readColumn(@Param("table") String table, @Param("column") String column);

    /**
     * Write a normalized canonical value back to one record's Object_Status column.
     *
     * @param table  the table name (from the registry)
     * @param column the status column name (from the registry)
     * @param id     the {@code char(36)} primary key of the record
     * @param value  the canonical Object_Status value to store
     * @return the number of affected rows (1 on success)
     */
    @Update("<script>"
            + "UPDATE ${table} SET ${column} = #{value} WHERE id = #{id}"
            + "</script>")
    int updateValue(@Param("table") String table,
                    @Param("column") String column,
                    @Param("id") String id,
                    @Param("value") String value);
}
