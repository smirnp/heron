// Copyright 2016 Twitter. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.twitter.heron.scheduler;

import java.util.Map;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.twitter.heron.api.HeronSubmitter;
import com.twitter.heron.api.HeronTopology;
import com.twitter.heron.api.bolt.BaseBasicBolt;
import com.twitter.heron.api.bolt.BasicOutputCollector;
import com.twitter.heron.api.generated.TopologyAPI;
import com.twitter.heron.api.spout.BaseRichSpout;
import com.twitter.heron.api.spout.SpoutOutputCollector;
import com.twitter.heron.api.topology.OutputFieldsDeclarer;
import com.twitter.heron.api.topology.TopologyBuilder;
import com.twitter.heron.api.topology.TopologyContext;
import com.twitter.heron.api.tuple.Tuple;
import com.twitter.heron.proto.system.ExecutionEnvironment;
import com.twitter.heron.spi.common.Config;
import com.twitter.heron.spi.common.ConfigKeys;
import com.twitter.heron.spi.common.Keys;
import com.twitter.heron.spi.common.PackingPlan;
import com.twitter.heron.spi.packing.IPacking;
import com.twitter.heron.spi.scheduler.ILauncher;
import com.twitter.heron.spi.statemgr.SchedulerStateManagerAdaptor;
import com.twitter.heron.spi.utils.Runtime;


public class LaunchRunnerTest {
  private static final String topologyName = "testTopology";
  private static final String cluster = "testCluster";
  private static final String role = "testRole";
  private static final String environ = "testEnviron";

  private static TopologyAPI.Config.KeyValue getConfig(String key, String value) {
    return TopologyAPI.Config.KeyValue.newBuilder().setKey(key).setValue(value).build();
  }

  public static TopologyAPI.Topology createTopology(com.twitter.heron.api.Config heronConfig) {
    TopologyBuilder builder = new TopologyBuilder();
    builder.setSpout("spout-1", new BaseRichSpout() {
      public void declareOutputFields(OutputFieldsDeclarer declarer) {
      }

      public void open(Map conf, TopologyContext context, SpoutOutputCollector collector) {
      }

      public void nextTuple() {
      }
    }, 2);
    builder.setBolt("bolt-1", new BaseBasicBolt() {
      public void execute(Tuple input, BasicOutputCollector collector) {
      }

      public void declareOutputFields(OutputFieldsDeclarer declarer) {
      }
    }, 1);
    HeronTopology heronTopology = builder.createTopology();
    try {
      HeronSubmitter.submitTopology(topologyName, heronConfig, heronTopology);
    } catch (Exception e) {
    }

    return heronTopology.
        setName(topologyName).
        setConfig(heronConfig).
        setState(TopologyAPI.TopologyState.RUNNING).
        getTopology();
  }

  private static Config createRunnerConfig() {
    Config config = Mockito.mock(Config.class);
    Mockito.when(config.getStringValue(ConfigKeys.get("TOPOLOGY_NAME"))).thenReturn(topologyName);
    Mockito.when(config.getStringValue(ConfigKeys.get("CLUSTER"))).thenReturn(cluster);
    Mockito.when(config.getStringValue(ConfigKeys.get("ROLE"))).thenReturn(role);
    Mockito.when(config.getStringValue(ConfigKeys.get("ENVIRON"))).thenReturn(environ);

    return config;
  }

  private static Config createRunnerRuntime() {
    Config runtime = Mockito.mock(Config.class);
    ILauncher launcher = Mockito.mock(ILauncher.class);
    IPacking packing = Mockito.mock(IPacking.class);
    SchedulerStateManagerAdaptor adaptor = Mockito.mock(SchedulerStateManagerAdaptor.class);
    TopologyAPI.Topology topology = createTopology(new com.twitter.heron.api.Config());

    Mockito.when(runtime.get(Keys.launcherClassInstance())).thenReturn(launcher);
    Mockito.when(runtime.get(Keys.packingClassInstance())).thenReturn(packing);
    Mockito.when(runtime.get(Keys.schedulerStateManagerAdaptor())).thenReturn(adaptor);
    Mockito.when(runtime.get(Keys.topologyDefinition())).thenReturn(topology);

    return runtime;
  }

  @Before
  public void setUp() throws Exception {
  }

  @Test
  public void testTrimTopology() throws Exception {
    LaunchRunner launchRunner = new LaunchRunner(createRunnerConfig(), createRunnerRuntime());
    TopologyAPI.Topology topologyBeforeTrimmed = createTopology(new com.twitter.heron.api.Config());
    TopologyAPI.Topology topologyAfterTrimmed = launchRunner.trimTopology(topologyBeforeTrimmed);

    for (TopologyAPI.Spout spout : topologyBeforeTrimmed.getSpoutsList()) {
      Assert.assertTrue(spout.getComp().hasJavaObject());
    }

    for (TopologyAPI.Bolt bolt : topologyBeforeTrimmed.getBoltsList()) {
      Assert.assertTrue(bolt.getComp().hasJavaObject());
    }

    for (TopologyAPI.Spout spout : topologyAfterTrimmed.getSpoutsList()) {
      Assert.assertFalse(spout.getComp().hasJavaObject());
    }

    for (TopologyAPI.Bolt bolt : topologyAfterTrimmed.getBoltsList()) {
      Assert.assertFalse(bolt.getComp().hasJavaObject());
    }
  }

  @Test
  public void testCreateExecutionState() throws Exception {
    LaunchRunner launchRunner = new LaunchRunner(createRunnerConfig(), createRunnerRuntime());
    ExecutionEnvironment.ExecutionState executionState = launchRunner.createExecutionState();

    Assert.assertTrue(executionState.isInitialized());

    Assert.assertEquals(topologyName, executionState.getTopologyName());
    Assert.assertEquals(cluster, executionState.getCluster());
    Assert.assertEquals(role, executionState.getRole());
    Assert.assertEquals(environ, executionState.getEnviron());
    Assert.assertEquals(System.getProperty("user.name"), executionState.getSubmissionUser());

    Assert.assertNotNull(executionState.getTopologyId());
    Assert.assertTrue(executionState.getSubmissionTime() <= (System.currentTimeMillis() / 1000));

    Assert.assertNotNull(executionState.getReleaseState());
    Assert.assertNotNull(executionState.getReleaseState().getReleaseVersion());
    Assert.assertNotNull(executionState.getReleaseState().getReleaseUsername());
  }

  @Test
  public void testPrepareLaunchFail() throws Exception {
    Config runtime = createRunnerRuntime();
    Config config = createRunnerConfig();
    ILauncher launcher = Runtime.launcherClassInstance(runtime);
    Mockito.when(launcher.prepareLaunch(Mockito.any(PackingPlan.class))).thenReturn(false);

    LaunchRunner launchRunner = new LaunchRunner(config, runtime);

    Assert.assertFalse(launchRunner.call());
    Mockito.verify(launcher).initialize(config, runtime);
    Mockito.verify(launcher, Mockito.never()).launch(Mockito.any(PackingPlan.class));

    SchedulerStateManagerAdaptor statemgr = Runtime.schedulerStateManagerAdaptor(runtime);
    Mockito.verify(statemgr, Mockito.never()).
        setExecutionState(Mockito.any(ExecutionEnvironment.ExecutionState.class), Mockito.anyString());
    Mockito.verify(statemgr, Mockito.never()).
        setTopology(Mockito.any(TopologyAPI.Topology.class), Mockito.anyString());
  }

  @Test
  public void testSetExecutionStateFail() throws Exception {
    Config runtime = createRunnerRuntime();
    Config config = createRunnerConfig();
    ILauncher launcher = Runtime.launcherClassInstance(runtime);
    Mockito.when(launcher.prepareLaunch(Mockito.any(PackingPlan.class))).thenReturn(true);

    LaunchRunner launchRunner = new LaunchRunner(config, runtime);

    SchedulerStateManagerAdaptor statemgr = Runtime.schedulerStateManagerAdaptor(runtime);
    Mockito.when(
        statemgr.setExecutionState(Mockito.any(ExecutionEnvironment.ExecutionState.class), Mockito.eq(topologyName))).
        thenReturn(false);

    Assert.assertFalse(launchRunner.call());

    Mockito.verify(launcher, Mockito.never()).launch(Mockito.any(PackingPlan.class));
  }

  @Test
  public void testSetTopologyFail() throws Exception {
    Config runtime = createRunnerRuntime();
    Config config = createRunnerConfig();
    ILauncher launcher = Runtime.launcherClassInstance(runtime);
    Mockito.when(launcher.prepareLaunch(Mockito.any(PackingPlan.class))).thenReturn(true);

    LaunchRunner launchRunner = new LaunchRunner(config, runtime);

    SchedulerStateManagerAdaptor statemgr = Runtime.schedulerStateManagerAdaptor(runtime);
    Mockito.when(
        statemgr.setTopology(Mockito.any(TopologyAPI.Topology.class), Mockito.eq(topologyName))).
        thenReturn(false);

    Assert.assertFalse(launchRunner.call());

    Mockito.verify(launcher, Mockito.never()).launch(Mockito.any(PackingPlan.class));
  }

  @Test
  public void testLaunchFail() throws Exception {
    Config runtime = createRunnerRuntime();
    Config config = createRunnerConfig();
    ILauncher launcher = Runtime.launcherClassInstance(runtime);
    Mockito.when(launcher.prepareLaunch(Mockito.any(PackingPlan.class))).thenReturn(true);

    SchedulerStateManagerAdaptor statemgr = Runtime.schedulerStateManagerAdaptor(runtime);
    Mockito.when(
        statemgr.setTopology(Mockito.any(TopologyAPI.Topology.class), Mockito.eq(topologyName))).
        thenReturn(true);
    Mockito.when(
        statemgr.setExecutionState(Mockito.any(ExecutionEnvironment.ExecutionState.class), Mockito.eq(topologyName))).
        thenReturn(true);

    LaunchRunner launchRunner = new LaunchRunner(config, runtime);
    Mockito.when(launcher.launch(Mockito.any(PackingPlan.class))).thenReturn(false);

    Assert.assertFalse(launchRunner.call());

    // Verify set && clean
    Mockito.verify(statemgr).setTopology(Mockito.any(TopologyAPI.Topology.class), Mockito.eq(topologyName));
    Mockito.verify(statemgr).setExecutionState(Mockito.any(ExecutionEnvironment.ExecutionState.class), Mockito.eq(topologyName));
    Mockito.verify(statemgr).deleteExecutionState(Mockito.eq(topologyName));
    Mockito.verify(statemgr).deleteTopology(Mockito.eq(topologyName));
  }

  @Test
  public void testPostLaunchFail() throws Exception {
    Config runtime = createRunnerRuntime();
    Config config = createRunnerConfig();
    ILauncher launcher = Runtime.launcherClassInstance(runtime);
    Mockito.when(launcher.prepareLaunch(Mockito.any(PackingPlan.class))).thenReturn(true);
    Mockito.when(launcher.launch(Mockito.any(PackingPlan.class))).thenReturn(true);

    SchedulerStateManagerAdaptor statemgr = Runtime.schedulerStateManagerAdaptor(runtime);
    Mockito.when(
        statemgr.setTopology(Mockito.any(TopologyAPI.Topology.class), Mockito.eq(topologyName))).
        thenReturn(true);
    Mockito.when(
        statemgr.setExecutionState(Mockito.any(ExecutionEnvironment.ExecutionState.class), Mockito.eq(topologyName))).
        thenReturn(true);

    LaunchRunner launchRunner = new LaunchRunner(config, runtime);

    Mockito.when(launcher.postLaunch(Mockito.any(PackingPlan.class))).thenReturn(false);
    Assert.assertFalse(launchRunner.call());

    // Verify set && clean
    Mockito.verify(statemgr).setTopology(Mockito.any(TopologyAPI.Topology.class), Mockito.eq(topologyName));
    Mockito.verify(statemgr).setExecutionState(Mockito.any(ExecutionEnvironment.ExecutionState.class), Mockito.eq(topologyName));
    Mockito.verify(statemgr).deleteExecutionState(Mockito.eq(topologyName));
    Mockito.verify(statemgr).deleteTopology(Mockito.eq(topologyName));
  }

  @Test
  public void testCallSuccess() throws Exception {
    Config runtime = createRunnerRuntime();
    Config config = createRunnerConfig();
    ILauncher launcher = Runtime.launcherClassInstance(runtime);
    Mockito.when(launcher.prepareLaunch(Mockito.any(PackingPlan.class))).thenReturn(true);
    Mockito.when(launcher.launch(Mockito.any(PackingPlan.class))).thenReturn(true);
    Mockito.when(launcher.postLaunch(Mockito.any(PackingPlan.class))).thenReturn(true);

    SchedulerStateManagerAdaptor statemgr = Runtime.schedulerStateManagerAdaptor(runtime);
    Mockito.when(
        statemgr.setTopology(Mockito.any(TopologyAPI.Topology.class), Mockito.eq(topologyName))).
        thenReturn(true);
    Mockito.when(
        statemgr.setExecutionState(Mockito.any(ExecutionEnvironment.ExecutionState.class), Mockito.eq(topologyName))).
        thenReturn(true);

    LaunchRunner launchRunner = new LaunchRunner(config, runtime);

    Assert.assertTrue(launchRunner.call());

    // Verify set && clean
    Mockito.verify(statemgr).setTopology(Mockito.any(TopologyAPI.Topology.class), Mockito.eq(topologyName));
    Mockito.verify(statemgr).setExecutionState(Mockito.any(ExecutionEnvironment.ExecutionState.class), Mockito.eq(topologyName));
    Mockito.verify(statemgr, Mockito.never()).deleteExecutionState(Mockito.eq(topologyName));
    Mockito.verify(statemgr, Mockito.never()).deleteTopology(Mockito.eq(topologyName));
  }
}
