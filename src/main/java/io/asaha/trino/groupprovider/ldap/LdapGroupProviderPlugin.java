package io.asaha.trino.groupprovider.ldap;

import io.trino.spi.Plugin;
import io.trino.spi.security.GroupProviderFactory;

import java.util.Collections;

public final class LdapGroupProviderPlugin implements Plugin {

    @Override
    public Iterable<GroupProviderFactory> getGroupProviderFactories() {
        return Collections.singletonList(new LdapGroupProviderFactory());
    }
}
