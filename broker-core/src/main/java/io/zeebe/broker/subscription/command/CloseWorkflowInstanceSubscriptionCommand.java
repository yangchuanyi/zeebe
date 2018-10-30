/*
 * Zeebe Broker Core
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.zeebe.broker.subscription.command;

import static io.zeebe.broker.subscription.CloseWorkflowInstanceSubscriptionDecoder.elementInstanceKeyNullValue;
import static io.zeebe.broker.subscription.CloseWorkflowInstanceSubscriptionDecoder.subscriptionPartitionIdNullValue;
import static io.zeebe.broker.subscription.CloseWorkflowInstanceSubscriptionDecoder.workflowInstanceKeyNullValue;

import io.zeebe.broker.subscription.CloseWorkflowInstanceSubscriptionDecoder;
import io.zeebe.broker.subscription.CloseWorkflowInstanceSubscriptionEncoder;
import io.zeebe.broker.util.SbeBufferWriterReader;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

public class CloseWorkflowInstanceSubscriptionCommand
    extends SbeBufferWriterReader<
        CloseWorkflowInstanceSubscriptionEncoder, CloseWorkflowInstanceSubscriptionDecoder> {

  private final CloseWorkflowInstanceSubscriptionEncoder encoder =
      new CloseWorkflowInstanceSubscriptionEncoder();
  private final CloseWorkflowInstanceSubscriptionDecoder decoder =
      new CloseWorkflowInstanceSubscriptionDecoder();

  private int subscriptionPartitionId;
  private long workflowInstanceKey;
  private long elementInstanceKey;

  @Override
  protected CloseWorkflowInstanceSubscriptionEncoder getBodyEncoder() {
    return encoder;
  }

  @Override
  protected CloseWorkflowInstanceSubscriptionDecoder getBodyDecoder() {
    return decoder;
  }

  @Override
  public void write(MutableDirectBuffer buffer, int offset) {
    super.write(buffer, offset);

    encoder
        .subscriptionPartitionId(subscriptionPartitionId)
        .workflowInstanceKey(workflowInstanceKey)
        .elementInstanceKey(elementInstanceKey);
  }

  @Override
  public void wrap(DirectBuffer buffer, int offset, int length) {
    super.wrap(buffer, offset, length);

    subscriptionPartitionId = decoder.subscriptionPartitionId();
    workflowInstanceKey = decoder.workflowInstanceKey();
    elementInstanceKey = decoder.elementInstanceKey();
  }

  @Override
  public void reset() {
    subscriptionPartitionId = subscriptionPartitionIdNullValue();
    workflowInstanceKey = workflowInstanceKeyNullValue();
    elementInstanceKey = elementInstanceKeyNullValue();
  }

  public int getSubscriptionPartitionId() {
    return subscriptionPartitionId;
  }

  public void setSubscriptionPartitionId(int subscriptionPartitionId) {
    this.subscriptionPartitionId = subscriptionPartitionId;
  }

  public long getWorkflowInstanceKey() {
    return workflowInstanceKey;
  }

  public void setWorkflowInstanceKey(long workflowInstanceKey) {
    this.workflowInstanceKey = workflowInstanceKey;
  }

  public long getElementInstanceKey() {
    return elementInstanceKey;
  }

  public void setElementInstanceKey(long elementInstanceKey) {
    this.elementInstanceKey = elementInstanceKey;
  }
}