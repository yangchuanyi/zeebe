/*
 * Copyright 2018-present Open Networking Foundation
 * Copyright © 2020 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.primitive.partition.impl;

import static io.atomix.primitive.partition.PartitionGroupMembershipEvent.Type.MEMBERS_CHANGED;
import static io.atomix.utils.concurrent.Threads.namedThreads;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import io.atomix.cluster.ClusterMembershipEvent;
import io.atomix.cluster.ClusterMembershipEventListener;
import io.atomix.cluster.ClusterMembershipService;
import io.atomix.cluster.Member;
import io.atomix.cluster.MemberId;
import io.atomix.cluster.messaging.ClusterCommunicationService;
import io.atomix.primitive.partition.ManagedPartitionGroup;
import io.atomix.primitive.partition.ManagedPartitionGroupMembershipService;
import io.atomix.primitive.partition.MemberGroupStrategy;
import io.atomix.primitive.partition.PartitionGroup;
import io.atomix.primitive.partition.PartitionGroupConfig;
import io.atomix.primitive.partition.PartitionGroupMembership;
import io.atomix.primitive.partition.PartitionGroupMembershipEvent;
import io.atomix.primitive.partition.PartitionGroupMembershipEventListener;
import io.atomix.primitive.partition.PartitionGroupMembershipService;
import io.atomix.primitive.partition.PartitionGroupTypeRegistry;
import io.atomix.utils.concurrent.Futures;
import io.atomix.utils.concurrent.SingleThreadContext;
import io.atomix.utils.concurrent.ThreadContext;
import io.atomix.utils.config.ConfigurationException;
import io.atomix.utils.event.AbstractListenerManager;
import io.atomix.utils.serializer.Namespace;
import io.atomix.utils.serializer.Namespaces;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default partition group membership service. */
public class DefaultPartitionGroupMembershipService
    extends AbstractListenerManager<
        PartitionGroupMembershipEvent, PartitionGroupMembershipEventListener>
    implements ManagedPartitionGroupMembershipService {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(DefaultPartitionGroupMembershipService.class);
  private static final String BOOTSTRAP_SUBJECT = "partition-group-bootstrap";
  //  private static final int[] FIBONACCI_NUMBERS = new int[] {1, 1, 2, 3, 5};
  //  private static final int MAX_PARTITION_GROUP_ATTEMPTS = 5;

  private final ClusterMembershipService membershipService;
  private final ClusterCommunicationService messagingService;
  //  private final Serializer serializer;
  private final Map<String, PartitionGroupMembership> groups = Maps.newConcurrentMap();
  private final AtomicBoolean started = new AtomicBoolean();
  private volatile ThreadContext threadContext;
  private final ClusterMembershipEventListener membershipEventListener =
      this::handleMembershipChange;

  @SuppressWarnings("unchecked")
  public DefaultPartitionGroupMembershipService(
      final ClusterMembershipService membershipService,
      final ClusterCommunicationService messagingService,
      final Collection<ManagedPartitionGroup> groups,
      final PartitionGroupTypeRegistry groupTypeRegistry) {
    this.membershipService = membershipService;
    this.messagingService = messagingService;
    groups.forEach(
        group -> {
          this.groups.put(
              group.name(),
              new PartitionGroupMembership(
                  group.name(),
                  group.config(),
                  ImmutableSet.of(membershipService.getLocalMember().id()),
                  false));
        });

    final Namespace.Builder namespaceBuilder =
        Namespace.builder()
            .register(Namespaces.BASIC)
            .register(MemberId.class)
            .register(PartitionGroupMembership.class)
            .register(PartitionGroupInfo.class)
            .register(PartitionGroupConfig.class)
            .register(MemberGroupStrategy.class);

    final List<PartitionGroup.Type> groupTypes =
        Lists.newArrayList(groupTypeRegistry.getGroupTypes());
    groupTypes.sort(Comparator.comparing(PartitionGroup.Type::name));
    for (final PartitionGroup.Type groupType : groupTypes) {
      namespaceBuilder.register(groupType.namespace());
    }

    //    serializer = Serializer.using(namespaceBuilder.build());
  }

  @Override
  public PartitionGroupMembership getMembership(final String group) {
    return groups.get(group);
  }

  @Override
  public Collection<PartitionGroupMembership> getMemberships() {
    return groups.values();
  }

  /** Handles a cluster membership change. */
  private void handleMembershipChange(final ClusterMembershipEvent event) {
    if (event.type() == ClusterMembershipEvent.Type.MEMBER_ADDED) {
      bootstrap(event.subject());
    } else if (event.type() == ClusterMembershipEvent.Type.MEMBER_REMOVED) {
      threadContext.execute(
          () -> {
            groups
                .values()
                .forEach(
                    group -> {
                      if (group.members().contains(event.subject().id())) {
                        final Set<MemberId> newMembers = Sets.newHashSet(group.members());
                        newMembers.remove(event.subject().id());
                        final PartitionGroupMembership newMembership =
                            new PartitionGroupMembership(
                                group.group(),
                                group.config(),
                                ImmutableSet.copyOf(newMembers),
                                false);
                        groups.put(group.group(), newMembership);
                        post(new PartitionGroupMembershipEvent(MEMBERS_CHANGED, newMembership));
                      }
                    });
          });
    }
  }

  /** Bootstraps the service. */
  private CompletableFuture<Void> bootstrap() {
    // try to bootstrap with known members
    // it is very likely that the known member on bootstrap are empty
    return Futures.allOf(
            membershipService.getMembers().stream()
                .filter(node -> !node.id().equals(membershipService.getLocalMember().id()))
                .map(this::bootstrap)
                .collect(Collectors.toList()))
        .thenApply(list -> null);
  }

  /** Bootstraps the service from the given node. */
  @SuppressWarnings("unchecked")
  private CompletableFuture<Void> bootstrap(final Member member) {
    return bootstrap(member, new CompletableFuture<>());
  }

  /** Bootstraps the service from the given node. */
  @SuppressWarnings("unchecked")
  private CompletableFuture<Void> bootstrap(
      final Member member, final CompletableFuture<Void> future) {
    LOGGER.error(
        "{} - Bootstrapping from member {}", membershipService.getLocalMember().id(), member);
    final var partitionGroupInfo =
        new PartitionGroupInfo(
            membershipService.getLocalMember().id(), Lists.newArrayList(groups.values()));
    //    messagingService
    //        .<PartitionGroupInfo, PartitionGroupInfo>send(
    //            BOOTSTRAP_SUBJECT,
    //            new PartitionGroupInfo(
    //                membershipService.getLocalMember().id(), Lists.newArrayList(groups.values())),
    //            serializer::encode,
    //            serializer::decode,
    //            member.id())
    //        .whenCompleteAsync(
    //            (info, error) -> {
    //              if (error == null) {
    try {
      updatePartitionGroups(partitionGroupInfo);
      future.complete(null);
    } catch (final Exception e) {
      future.completeExceptionally(e);
    }
    //              } else {
    //                error = Throwables.getRootCause(error);
    //                if (error instanceof MessagingException.NoRemoteHandler
    //                    || error instanceof TimeoutException) {
    //                  threadContext.schedule(Duration.ofSeconds(1), () -> bootstrap(member,
    // future));
    //                } else {
    //                  LOGGER.debug(
    //                      "{} - Failed to bootstrap from member {}",
    //                      membershipService.getLocalMember().id(),
    //                      member,
    //                      error);
    //                  future.complete(null);
    //                }
    //              }
    //            },
    //            threadContext);
    return future;
  }

  private void updatePartitionGroups(final PartitionGroupInfo info) {

    for (final PartitionGroupMembership newMembership : info.groups) {
      final PartitionGroupMembership oldMembership = groups.get(newMembership.group());
      if (oldMembership == null) {
        groups.put(newMembership.group(), newMembership);
        post(new PartitionGroupMembershipEvent(MEMBERS_CHANGED, newMembership));
        LOGGER.info(
            "{} - Bootstrapped partition group {} from {}",
            membershipService.getLocalMember().id(),
            newMembership,
            info.memberId);
      } else if (!oldMembership.group().equals(newMembership.group())
          || !oldMembership
              .config()
              .getType()
              .name()
              .equals(newMembership.config().getType().name())) {
        throw new ConfigurationException(
            "Duplicate partition group " + newMembership.group() + " detected");
      } else {
        final Set<MemberId> newMembers =
            Stream.concat(oldMembership.members().stream(), newMembership.members().stream())
                .filter(memberId -> membershipService.getMember(memberId) != null)
                .collect(Collectors.toSet());
        if (!Sets.difference(newMembers, oldMembership.members()).isEmpty()) {
          final PartitionGroupMembership newGroup =
              new PartitionGroupMembership(
                  oldMembership.group(),
                  oldMembership.config(),
                  ImmutableSet.copyOf(newMembers),
                  false);
          groups.put(oldMembership.group(), newGroup);
          post(new PartitionGroupMembershipEvent(MEMBERS_CHANGED, newGroup));
          LOGGER.debug(
              "{} - Updated partition group {} from {}",
              membershipService.getLocalMember().id(),
              newGroup,
              info.memberId);
        }
      }
    }
  }
  //
  //  private PartitionGroupInfo handleBootstrap(final PartitionGroupInfo info) {
  //    try {
  //      updatePartitionGroups(info);
  //    } catch (final Exception e) {
  //      // Log the exception
  //      LOGGER.warn("{}", e.getMessage());
  //    }
  //    return new PartitionGroupInfo(
  //        membershipService.getLocalMember().id(), Lists.newArrayList(groups.values()));
  //  }

  @Override
  public CompletableFuture<PartitionGroupMembershipService> start() {
    threadContext =
        new SingleThreadContext(
            namedThreads("atomix-partition-group-membership-service-%d", LOGGER));
    membershipService.addListener(membershipEventListener);
    //    messagingService.subscribe(
    //        BOOTSTRAP_SUBJECT,
    //        serializer::decode,
    //        this::handleBootstrap,
    //        serializer::encode,
    //        threadContext);
    return bootstrap()
        .thenApply(
            v -> {
              LOGGER.error("Started {}", this.getClass());
              started.set(true);
              return this;
            });
  }

  @Override
  public boolean isRunning() {
    return started.get();
  }

  @Override
  @SuppressWarnings("unchecked")
  public CompletableFuture<Void> stop() {
    membershipService.removeListener(membershipEventListener);
    messagingService.unsubscribe(BOOTSTRAP_SUBJECT);
    final ThreadContext threadContext = this.threadContext;
    if (threadContext != null) {
      threadContext.close();
    }
    LOGGER.info("Stopped");
    started.set(false);
    return CompletableFuture.completedFuture(null);
  }

  private static class PartitionGroupInfo {
    private final MemberId memberId;
    private final Collection<PartitionGroupMembership> groups;

    PartitionGroupInfo(final MemberId memberId, final Collection<PartitionGroupMembership> groups) {
      this.memberId = memberId;
      this.groups = groups;
    }
  }
}