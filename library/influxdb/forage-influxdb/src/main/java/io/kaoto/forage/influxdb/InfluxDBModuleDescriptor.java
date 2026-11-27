package io.kaoto.forage.influxdb;

import java.util.List;
import java.util.Map;
import org.influxdb.InfluxDB;
import io.kaoto.forage.core.common.ForageModuleDescriptor;
import io.kaoto.forage.core.common.QuarkusBean;

public class InfluxDBModuleDescriptor implements ForageModuleDescriptor<InfluxDBConfig, InfluxDBProvider> {
    @Override
    public String modulePrefix() {
        return "influxdb";
    }

    private static final QuarkusBean QUARKUS_BEAN = new QuarkusBean(
            List.of(
                    "#class:org.influxdb.InfluxDBFactory#connect('{url}','{username}','{password}')",
                    "#class:org.influxdb.InfluxDBFactory#connect('{url}')"),
            Map.of(),
            Map.of(),
            List.of(),
            List.of("org.influxdb:influxdb-java"),
            List.of(),
            List.of());

    /**
     * The Quarkus export creates the client as {@link InfluxDBProvider} does, with the factory method of the set keys
     * (no extension creates it from config). Read by the catalog build.
     */
    public QuarkusBean quarkusBean() {
        return QUARKUS_BEAN;
    }

    @Override
    public InfluxDBConfig createConfig(String prefix) {
        return new InfluxDBConfig(prefix);
    }

    @Override
    public Class<InfluxDBProvider> providerClass() {
        return InfluxDBProvider.class;
    }

    @Override
    public String resolveProviderClassName(InfluxDBConfig config) {
        return InfluxDBProvider.class.getName();
    }

    @Override
    public String defaultBeanName() {
        return "influxdb";
    }

    @Override
    public Class<?> primaryBeanClass() {
        return InfluxDB.class;
    }

    @Override
    public boolean transactionEnabled(InfluxDBConfig config) {
        return false;
    }

    @Override
    public String destroyMethodName(String prefix) {
        return "close";
    }
}
