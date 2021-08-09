package io.asaha.trino.groupprovider.ldap;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import io.trino.spi.classloader.ThreadContextClassLoader;
import io.trino.spi.security.GroupProvider;

import javax.naming.AuthenticationException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import io.airlift.log.Logger;
import io.airlift.units.Duration;

import static javax.naming.Context.*;

public class LdapGroupProvider implements GroupProvider {

    private static final Logger log = Logger.get(LdapGroupProvider.class);
    private final String ldapAdminUser;
    private final String ldapAdminPassword;
    private final String userBaseDistinguishedName;
    private final String userBaseDistinguishedNameSecondary;
    private final String userSearchFilter;
    private final String groupFilter;
    private final String maxRetryCount;
    private final String retryInterval;

    private final Map<String, String> basicEnvironment;
    private final LoadingCache<String, Set<String>> groupListCache;
    private boolean isIgnoreReferrals = false;

    LdapGroupProvider(Map<String, String> config){
        String ldapUrl = config.get("ldap.url");
        this.ldapAdminUser = config.get("ldap.admin-user");
        this.ldapAdminPassword = config.get("ldap.admin-password");
        this.userBaseDistinguishedName = config.get("ldap.user-base-dn");
        this.userBaseDistinguishedNameSecondary = config.getOrDefault("ldap.user-base-dn-secondary", userBaseDistinguishedName);
        this.userSearchFilter = config.get("ldap.user-search-filter");
        this.groupFilter = config.getOrDefault("ldap.group-filter","ou");
        String ldapCacheTtl = config.getOrDefault("ldap.cache-ttl","1h");
        this.maxRetryCount = config.getOrDefault("ldap.max-retry-count", "5");
        this.retryInterval = config.getOrDefault("ldap.retry-interval","2s");

        this.basicEnvironment = ImmutableMap.<String, String>builder()
                .put(INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
                .put(PROVIDER_URL, ldapUrl)
                .put(REFERRAL, isIgnoreReferrals ? "ignore" : "follow")
                .build();

        this.groupListCache = CacheBuilder.newBuilder()
                .expireAfterWrite(Duration.valueOf(ldapCacheTtl).toMillis(),TimeUnit.MILLISECONDS)
                .maximumSize(1000)
                .build(CacheLoader.from(this::getLdapGroups));
    }

    private static String replaceUser(String pattern, String user)
    {
        return pattern.replace("${USER}", user);
    }

    private Map<String, String> createEnvironment(String userDistinguishedName, String password)
    {
        ImmutableMap.Builder<String, String> environment = ImmutableMap.<String, String>builder()
                .putAll(basicEnvironment)
                .put(SECURITY_AUTHENTICATION, "simple")
                .put(SECURITY_PRINCIPAL, userDistinguishedName)
                .put(SECURITY_CREDENTIALS, password);
        return environment.build();
    }

    private DirContext createUserDirContext(String userDistinguishedName, String password, int retryCount)
            throws NamingException
    {
        Map<String, String> environment = createEnvironment(userDistinguishedName, password);
        DirContext dirContext;
        try {
            //return new InitialDirContext(new Hashtable<>(environment));
            dirContext = new InitialDirContext(new Hashtable<>(environment));
            // log.info("Password validation successful for user DN [%s]", userDistinguishedName);
        }
        catch (AuthenticationException e) {
            log.error("Password validation failed for user DN [%s]: %s", userDistinguishedName, e.getMessage());
            throw new AuthenticationException("Invalid credentials");
        }
        catch (NamingException ne) {
            log.error(ne, "NamingException for attempt: [%s]", retryCount);
            if (retryCount >= Integer.parseInt(maxRetryCount)) {
                throw ne;
            }
            try {
                Thread.sleep(Duration.valueOf(retryInterval).toMillis());
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
            dirContext = createUserDirContext(userDistinguishedName, password, ++retryCount);
        }
        return dirContext;
    }

    private NamingEnumeration<SearchResult> searchGroupMembership(String user, String userBaseDN, DirContext context)
            throws NamingException
    {
        SearchControls searchControls = new SearchControls();
        String[] attrIDs = {"memberOf"};
        searchControls.setReturningAttributes(attrIDs);
        searchControls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        return context.search(userBaseDN, replaceUser(userSearchFilter, user), searchControls);
    }

    private Set<String> getLdapGroups(String user) {
        try {
            DirContext dirContext = createUserDirContext(ldapAdminUser, ldapAdminPassword, 0);
            Set<String> groupSet = new HashSet<String>();
            try{
                NamingEnumeration<SearchResult> search = searchGroupMembership(user, userBaseDistinguishedName, dirContext);
                try {
                    if (!search.hasMore()) {
                        log.debug("User [%s] not found in primary base dn", user);
                        search.close();
                        search = searchGroupMembership(user, userBaseDistinguishedNameSecondary, dirContext);
                        if (!search.hasMore()) {
                            log.warn("User [%s] not found in secondary base dn", user);
                            return Collections.emptySet();
                        }
                        log.debug("User [%s] found in secondary base dn", user);
                    }
                    //String userDistinguishedName = search.next().getAttributes();
                    Attribute groupAttributes = search.next().getAttributes().get("memberOf");
                    if (groupAttributes == null) {
                        log.debug("User [%s] is not member of any group", user);
                        return Collections.emptySet();
                    }

                    log.debug("User: [%s], Group Details: [%s]", user, groupAttributes.toString());
                    for (int ix= 0; ix < groupAttributes.size(); ix++){
                        String group = groupAttributes.get(ix).toString();
                        if (group.toLowerCase().contains(groupFilter.toLowerCase())){
                            groupSet.add(group.split("(?i),ou=")[0].split("=")[1]);
                        }
                    }
                }
                finally {
                    search.close();
                }
            }
            finally {
                dirContext.close();
            }
            log.info("User: [%s], LDAP Groups: %s", user, groupSet.toString());
            return groupSet;

        } catch (NamingException e) {
            log.error(e, "NamingException for User: [%s]", user);
            return Collections.emptySet();
        }
    }

    @Override
    public Set<String> getGroups(String user)  {
        try (ThreadContextClassLoader ignored = new ThreadContextClassLoader(getClass().getClassLoader())) {
            Set<String> groups = groupListCache.getUnchecked(user);
            log.debug("User: [%s], Groups: [%s]", user, groups.toString());
            return groups;
        }
    }
}

