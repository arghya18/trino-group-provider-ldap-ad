package io.asaha.trino.groupprovider.ldap;

import io.trino.spi.security.GroupProvider;
import io.trino.spi.security.GroupProviderFactory;

import java.util.Map;

public class LdapGroupProviderFactory implements GroupProviderFactory {

    @Override
    public String getName() {
        return "ldap-ad";
    }

    @Override
    public GroupProvider create(Map<String, String> config) {
        if (config.isEmpty()) {
            throw new IllegalArgumentException("this group provider requires configuration properties");
        }
        return new LdapGroupProvider(config);
    }
}
