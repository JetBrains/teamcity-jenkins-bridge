package com.jetbrains.teamcity.jenkinsbridge.settings;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class MirroredChildJobTest {
  @Test
  public void childKeepsItsParentMappingAndConcreteJobIdentity() {
    MirroredJob parent = new MirroredJob(
        "conn1", "team/pipeline", "Proj_Mirror", "Proj / Mirror", 3, true);
    MirroredChildJob child = new MirroredChildJob(parent, "team/pipeline/feature%2Flogin");

    assertSame(parent, child.parent());
    assertSame(parent, child.mapping());
    assertSame(parent, parent.mapping());
    assertEquals("team/pipeline", parent.jenkinsJob());
    assertEquals("team/pipeline/feature%2Flogin", child.jenkinsJob());
    assertEquals("Proj_Mirror::team/pipeline/feature%2Flogin", child.getMirrorKeyPrefix());
    assertEquals("Proj_Mirror::team/pipeline", parent.getMirrorKeyPrefix());
  }

  @Test
  public void childRequiresAMultibranchParent() {
    MirroredJob regular = new MirroredJob("conn1", "job", "Bt", "Build", 1, false);

    assertThrows(IllegalArgumentException.class, () -> new MirroredChildJob(regular, "job/main"));
  }

  @Test
  public void childRequiresAConcreteJobPath() {
    MirroredJob parent = new MirroredJob("conn1", "job", "Bt", "Build", 1, true);

    assertThrows(IllegalArgumentException.class, () -> new MirroredChildJob(parent, " "));
  }
}
