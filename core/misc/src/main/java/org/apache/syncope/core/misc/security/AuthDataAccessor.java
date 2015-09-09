/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.syncope.core.misc.security;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.annotation.Resource;
import org.apache.commons.collections4.Closure;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.SetUtils;
import org.apache.commons.collections4.Transformer;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.syncope.common.lib.SyncopeConstants;
import org.apache.syncope.common.lib.types.AuditElements;
import org.apache.syncope.common.lib.types.Entitlement;
import org.apache.syncope.core.misc.AuditManager;
import org.apache.syncope.core.misc.MappingUtils;
import org.apache.syncope.core.misc.RealmUtils;
import org.apache.syncope.core.persistence.api.dao.AnyTypeDAO;
import org.apache.syncope.core.persistence.api.dao.ConfDAO;
import org.apache.syncope.core.persistence.api.dao.DomainDAO;
import org.apache.syncope.core.persistence.api.dao.GroupDAO;
import org.apache.syncope.core.persistence.api.dao.RealmDAO;
import org.apache.syncope.core.persistence.api.dao.UserDAO;
import org.apache.syncope.core.persistence.api.entity.Domain;
import org.apache.syncope.core.persistence.api.entity.Realm;
import org.apache.syncope.core.persistence.api.entity.Role;
import org.apache.syncope.core.persistence.api.entity.conf.CPlainAttr;
import org.apache.syncope.core.persistence.api.entity.group.Group;
import org.apache.syncope.core.persistence.api.entity.resource.ExternalResource;
import org.apache.syncope.core.persistence.api.entity.user.User;
import org.apache.syncope.core.provisioning.api.ConnectorFactory;
import org.identityconnectors.framework.common.objects.Uid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain-sensible (via {@code @Transactional} access to authentication / authorization data.
 *
 * @see SyncopeAuthenticationProvider
 * @see SyncopeAuthenticationDetails
 */
public class AuthDataAccessor {

    protected static final Logger LOG = LoggerFactory.getLogger(AuthDataAccessor.class);

    @Resource(name = "adminUser")
    protected String adminUser;

    @Resource(name = "anonymousUser")
    protected String anonymousUser;

    @Autowired
    protected DomainDAO domainDAO;

    @Autowired
    protected ConfDAO confDAO;

    @Autowired
    protected RealmDAO realmDAO;

    @Autowired
    protected UserDAO userDAO;

    @Autowired
    protected GroupDAO groupDAO;

    @Autowired
    protected AnyTypeDAO anyTypeDAO;

    @Autowired
    protected ConnectorFactory connFactory;

    @Autowired
    protected AuditManager auditManager;

    protected final Encryptor encryptor = Encryptor.getInstance();

    @Transactional(readOnly = true)
    public Domain findDomain(final String key) {
        Domain domain = domainDAO.find(key);
        if (domain == null) {
            throw new AuthenticationServiceException("Could not find domain " + key);
        }
        return domain;
    }

    @Transactional(noRollbackFor = DisabledException.class)
    public Pair<Long, Boolean> authenticate(final Authentication authentication) {
        Long key = null;
        Boolean authenticated = false;

        User user = userDAO.find(authentication.getName());
        if (user != null) {
            key = user.getKey();

            if (user.isSuspended() != null && user.isSuspended()) {
                throw new DisabledException("User " + user.getUsername() + " is suspended");
            }

            CPlainAttr authStatuses = confDAO.find("authentication.statuses");
            if (authStatuses != null && !authStatuses.getValuesAsStrings().contains(user.getStatus())) {
                throw new DisabledException("User " + user.getUsername() + " not allowed to authenticate");
            }

            boolean userModified = false;
            authenticated = authenticate(user, authentication.getCredentials().toString());
            if (authenticated) {
                if (confDAO.find("log.lastlogindate", Boolean.toString(true)).getValues().get(0).getBooleanValue()) {
                    user.setLastLoginDate(new Date());
                    userModified = true;
                }

                if (user.getFailedLogins() != 0) {
                    user.setFailedLogins(0);
                    userModified = true;
                }

            } else {
                user.setFailedLogins(user.getFailedLogins() + 1);
                userModified = true;
            }

            if (userModified) {
                userDAO.save(user);
            }
        }

        return ImmutablePair.of(key, authenticated);
    }

    protected boolean authenticate(final User user, final String password) {
        boolean authenticated = encryptor.verify(password, user.getCipherAlgorithm(), user.getPassword());
        LOG.debug("{} authenticated on internal storage: {}", user.getUsername(), authenticated);

        for (Iterator<? extends ExternalResource> itor = getPassthroughResources(user).iterator();
                itor.hasNext() && !authenticated;) {

            ExternalResource resource = itor.next();
            String connObjectKey = null;
            try {
                connObjectKey = MappingUtils.getConnObjectKeyValue(user, resource.getProvision(anyTypeDAO.findUser()));
                Uid uid = connFactory.getConnector(resource).authenticate(connObjectKey, password, null);
                if (uid != null) {
                    authenticated = true;
                }
            } catch (Exception e) {
                LOG.debug("Could not authenticate {} on {}", user.getUsername(), resource.getKey(), e);
            }
            LOG.debug("{} authenticated on {} as {}: {}",
                    user.getUsername(), resource.getKey(), connObjectKey, authenticated);
        }

        return authenticated;
    }

    protected Set<? extends ExternalResource> getPassthroughResources(final User user) {
        Set<? extends ExternalResource> result = null;

        // 1. look for assigned resources, pick the ones whose account policy has authentication resources
        for (ExternalResource resource : userDAO.findAllResources(user)) {
            if (resource.getAccountPolicy() != null && !resource.getAccountPolicy().getResources().isEmpty()) {
                if (result == null) {
                    result = resource.getAccountPolicy().getResources();
                } else {
                    result.retainAll(resource.getAccountPolicy().getResources());
                }
            }
        }

        // 2. look for realms, pick the ones whose account policy has authentication resources
        for (Realm realm : realmDAO.findAncestors(user.getRealm())) {
            if (realm.getAccountPolicy() != null && !realm.getAccountPolicy().getResources().isEmpty()) {
                if (result == null) {
                    result = realm.getAccountPolicy().getResources();
                } else {
                    result.retainAll(realm.getAccountPolicy().getResources());
                }
            }
        }

        return SetUtils.emptyIfNull(result);
    }

    @Transactional(readOnly = true)
    public void audit(
            final AuditElements.EventCategoryType type,
            final String category,
            final String subcategory,
            final String event,
            final AuditElements.Result result,
            final Object before,
            final Object output,
            final Object... input) {

        auditManager.audit(type, category, subcategory, event, result, before, output, input);
    }

    @Transactional
    public Set<SyncopeGrantedAuthority> load(final String username) {
        final Set<SyncopeGrantedAuthority> authorities = new HashSet<>();
        if (anonymousUser.equals(username)) {
            authorities.add(new SyncopeGrantedAuthority(Entitlement.ANONYMOUS));
        } else if (adminUser.equals(username)) {
            CollectionUtils.collect(Entitlement.values(), new Transformer<String, SyncopeGrantedAuthority>() {

                @Override
                public SyncopeGrantedAuthority transform(final String entitlement) {
                    return new SyncopeGrantedAuthority(entitlement, SyncopeConstants.ROOT_REALM);
                }
            }, authorities);
        } else {
            User user = userDAO.find(username);
            if (user == null) {
                throw new UsernameNotFoundException("Could not find any user with id " + username);
            }

            if (user.isMustChangePassword()) {
                authorities.add(new SyncopeGrantedAuthority(Entitlement.MUST_CHANGE_PASSWORD));
            } else {
                // Give entitlements as assigned by roles (with realms, where applicable) - assigned either
                // statically and dynamically
                for (final Role role : userDAO.findAllRoles(user)) {
                    CollectionUtils.forAllDo(role.getEntitlements(), new Closure<String>() {

                        @Override
                        public void execute(final String entitlement) {
                            SyncopeGrantedAuthority authority = new SyncopeGrantedAuthority(entitlement);
                            authorities.add(authority);

                            List<String> realmFullPahs = new ArrayList<>();
                            CollectionUtils.collect(role.getRealms(), new Transformer<Realm, String>() {

                                @Override
                                public String transform(final Realm realm) {
                                    return realm.getFullPath();
                                }
                            }, realmFullPahs);
                            authority.addRealms(realmFullPahs);
                        }
                    });
                }

                // Give group entitlements for owned groups
                for (Group group : groupDAO.findOwnedByUser(user.getKey())) {
                    for (String entitlement : Arrays.asList(
                            Entitlement.GROUP_READ, Entitlement.GROUP_UPDATE, Entitlement.GROUP_DELETE)) {

                        SyncopeGrantedAuthority authority = new SyncopeGrantedAuthority(entitlement);
                        authority.addRealm(
                                RealmUtils.getGroupOwnerRealm(group.getRealm().getFullPath(), group.getKey()));
                        authorities.add(authority);
                    }
                }
            }
        }

        return authorities;
    }
}
