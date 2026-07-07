package com.adpilot.modules.advertising.mapper;

import java.math.BigDecimal;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.adpilot.modules.advertising.support.AcosValueRow;

/**
 * Generic, column-parameterized access used by the ACoS historical migration (Requirement 17.5) to
 * read and rewrite one ACoS column at a time.
 *
 * <p>The {@code table} and {@code column} names are interpolated with {@code ${...}} because MyBatis
 * cannot bind an identifier as a positional parameter. This is safe here: both names originate
 * exclusively from the fixed, compile-time {@code AcosColumnRegistry} and never from user input, so
 * there is no SQL-injection surface. The actual data <em>value</em> is always bound with {@code #{}}.</p>
 */
@Mapper
public interface AcosColumnMigrationMapper {

    /**
     * Read every non-null {@code (id, value)} pair from one ACoS column.
     *
     * @param table  the table name (from the registry)
     * @param column the ACoS column name (from the registry)
     * @return the rows whose ACoS value is non-null, in primary-key order
     */
    @Select("<script>"
            + "SELECT id AS id, ${column} AS value "
            + "FROM ${table} "
            + "WHERE ${column} IS NOT NULL "
            + "ORDER BY id"
            + "</script>")
    List<AcosValueRow> readColumn(@Param("table") String table, @Param("column") String column);

    /**
     * Write a converted decimal-ratio value back to one record's ACoS column.
     *
     * @param table  the table name (from the registry)
     * @param column the ACoS column name (from the registry)
     * @param id     the {@code char(36)} primary key of the record
     * @param value  the canonical decimal-ratio value to store
     * @return the number of affected rows (1 on success)
     */
    @Update("<script>"
            + "UPDATE ${table} SET ${column} = #{value} WHERE id = #{id}"
            + "</script>")
    int updateValue(@Param("table") String table,
                    @Param("column") String column,
                    @Param("id") String id,
                    @Param("value") BigDecimal value);
}
