/**
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
package org.apache.pinot.controller.helix.core.assignment.segment;

import com.google.common.base.Preconditions;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.pinot.common.assignment.InstancePartitions;
import org.apache.pinot.common.tier.Tier;
import org.apache.pinot.controller.helix.core.assignment.segment.strategy.DimTableSegmentAssignmentStrategy;
import org.apache.pinot.controller.helix.core.assignment.segment.strategy.SegmentAssignmentStrategy;
import org.apache.pinot.controller.helix.core.assignment.segment.strategy.SegmentAssignmentStrategyFactory;
import org.apache.pinot.spi.config.table.TableConfig;
import org.apache.pinot.spi.config.table.assignment.InstancePartitionsType;
import org.apache.pinot.spi.utils.RebalanceConfigConstants;


/**
 * Segment assignment for offline table.
 */
public class OfflineSegmentAssignment extends BaseSegmentAssignment {

  @Override
  protected void setSegmentAssignmentStrategyMap(
      Map<InstancePartitionsType, InstancePartitions> instancePartitionsMap) {
    _assignmentStrategyMap = SegmentAssignmentStrategyFactory.getSegmentAssignmentStrategy(_helixManager,
        _tableConfig, instancePartitionsMap);
  }

  @Override
  protected int getReplication(TableConfig tableConfig) {
    return tableConfig.getValidationConfig().getReplicationNumber();
  }

  @Override
  public List<String> assignSegment(String segmentName, Map<String, Map<String, String>> currentAssignment,
      Map<InstancePartitionsType, InstancePartitions> instancePartitionsMap) {
    SegmentAssignmentStrategy segmentAssignmentStrategy = _assignmentStrategyMap.get(InstancePartitionsType.OFFLINE);
    // Need to check assignmentStrategy for Dim Tables as following pre conditions state would return false
    if (segmentAssignmentStrategy instanceof DimTableSegmentAssignmentStrategy) {
      return segmentAssignmentStrategy.assignSegment(segmentName,
          currentAssignment, null, InstancePartitionsType.OFFLINE);
    }
    InstancePartitions instancePartitions = instancePartitionsMap.get(InstancePartitionsType.OFFLINE);
    Preconditions.checkState(instancePartitions != null, "Failed to find OFFLINE instance partitions for table: %s",
        _tableNameWithType);
    _logger.info("Assigning segment: {} with instance partitions: {} for table: {}", segmentName, instancePartitions,
        _tableNameWithType);
    List<String> instancesAssigned = assignSegment(segmentName, currentAssignment,
            instancePartitions, segmentAssignmentStrategy, InstancePartitionsType.OFFLINE);
    _logger.info("Assigned segment: {} to instances: {} for table: {}", segmentName, instancesAssigned,
        _tableNameWithType);
    return instancesAssigned;
  }

  @Override
  public Map<String, Map<String, String>> rebalanceTable(Map<String, Map<String, String>> currentAssignment,
      Map<InstancePartitionsType, InstancePartitions> instancePartitionsMap, @Nullable List<Tier> sortedTiers,
      @Nullable Map<String, InstancePartitions> tierInstancePartitionsMap, Configuration config) {
    InstancePartitions offlineInstancePartitions = instancePartitionsMap.get(InstancePartitionsType.OFFLINE);
    SegmentAssignmentStrategy segmentAssignmentStrategy = _assignmentStrategyMap.get(InstancePartitionsType.OFFLINE);
    // Need to check assignmentStrategy for Dim Tables as following pre conditions state would return false
    if (segmentAssignmentStrategy instanceof DimTableSegmentAssignmentStrategy) {
      return segmentAssignmentStrategy.reassignSegments(currentAssignment,
          offlineInstancePartitions, InstancePartitionsType.OFFLINE);
    }

    Preconditions.checkState(offlineInstancePartitions != null,
        "Failed to find OFFLINE instance partitions for table: %s", _tableNameWithType);
    boolean bootstrap =
        config.getBoolean(RebalanceConfigConstants.BOOTSTRAP, RebalanceConfigConstants.DEFAULT_BOOTSTRAP);

    // Rebalance tiers first
    Pair<List<Map<String, Map<String, String>>>, Map<String, Map<String, String>>> pair =
        rebalanceTiers(currentAssignment, sortedTiers, tierInstancePartitionsMap, bootstrap,
            segmentAssignmentStrategy, InstancePartitionsType.OFFLINE);
    List<Map<String, Map<String, String>>> newTierAssignments = pair.getLeft();
    Map<String, Map<String, String>> nonTierAssignment = pair.getRight();

    _logger.info("Rebalancing table: {} with instance partitions: {}, bootstrap: {}", _tableNameWithType,
        offlineInstancePartitions, bootstrap);
    Map<String, Map<String, String>> newAssignment =
        reassignSegments(InstancePartitionsType.OFFLINE.toString(), nonTierAssignment, offlineInstancePartitions,
            bootstrap, segmentAssignmentStrategy, InstancePartitionsType.OFFLINE);

    // Add tier assignments, if available
    if (CollectionUtils.isNotEmpty(newTierAssignments)) {
      newTierAssignments.forEach(newAssignment::putAll);
    }

    _logger.info("Rebalanced table: {}, number of segments to be moved to each instance: {}", _tableNameWithType,
        SegmentAssignmentUtils.getNumSegmentsToBeMovedPerInstance(currentAssignment, newAssignment));
    return newAssignment;
  }
}
