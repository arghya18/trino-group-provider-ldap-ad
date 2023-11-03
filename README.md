# Overview

Trino Group Provider LDAP is a Trino (formerly Presto SQL) plugin to map user names to groups using an LDAP server 

Its an implementation of Trino Group Provider, which will map Corporate/Local LDAP groups with AD users.
The groups can be further used for system and catalog authorization and RBAC implementation based on AD groups.


## Build

The artifacts are also present in the [Release](https://github.com/arghya18/trino-group-provider-ldap-ad/releases) of this project, incase you do not want to build this project.

```
mvn clean package
```


## Deploy

### Copy artifacts

Copy the following artifacts from build or from release to the Trino plugin folder (`<path_to_trino>/plugin/ldap-ad/`)

```
target/trino-group-provider-ldap-ad-1.0/*.jar
```

### Prepare configuration file

Create `<path_to_trino_config>/group-provider.properties` with the following required parameters, e.g.:

```
group-provider.name=ldap-ad
ldap.url=ldaps://ad.company.com:636
ldap.admin-user=admin@ad.company.com
ldap.admin-password=admin-password
ldap.user-base-dn=OU=Sites,DC=ad,DC=company,DC=com
ldap.user-search-filter=(&(objectClass=person)(sAMAccountName=${USER})) 
```

#### Optional Parameters

| Configuration                  | Default               | Description                                 |
|--------------------------------|-----------------------|---------------------------------------------|
| `ldap.user-base-dn-secondary`  | **ldap.user-base-dn** | Secondary base dn.                          |
| `ldap.group-filter`            | **ou**                | Group Filter.                               |
| `ldap.cache-ttl`               | **1h**                | Cache ttl for LDAP groups.                  |
| `ldap.max-retry-count`         | **5**                 | Retry count.                                |
| `ldap.retry-interval`          | **2s**                | Retry interval.                             |
| `ldap.ssl.keystore.path`       | **null**              | The path to the PEM or JKS keystore file.   |
| `ldap.ssl.keystore.password`   | **null**              | Password for the key store.                 |
| `ldap.ssl.truststore.path`     | **null**              | The path to the PEM or JKS truststore file. |
| `ldap.ssl.truststore.password` | **null**              | Password for the truststore.                |


### Import LDAP server SSL certificate

Import SSL certificates in Trino coordinator required to connect to LDAP server using keytool, below is one example

```
sudo keytool -import -alias ldap_cert -keystore $JAVA_HOME/lib/security/cacerts -file <path_to_ldap_cert>.pem -storepass changeit -noprompt
```

If you can't or don't want to import SSL certificates in Trino cacerts keystore, you can use `ldap.ssl.*` parameters to specify   