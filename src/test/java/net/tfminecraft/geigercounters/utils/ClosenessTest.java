package net.tfminecraft.geigercounters.utils;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ClosenessTest {

  @Test
  void fullAtNearEmptyAtFarAndClampedOutside() {
    assertEquals(1.0, Closeness.logarithmic(0, 10, 1000));
    assertEquals(1.0, Closeness.logarithmic(10, 10, 1000));
    assertEquals(0.0, Closeness.logarithmic(1000, 10, 1000));
    assertEquals(0.0, Closeness.logarithmic(5000, 10, 1000));
  }

  @Test
  void eachTenfoldStepFillsTheSameAmount() {
    assertEquals(0.5, Closeness.logarithmic(100, 10, 1000), 1e-9);
    double half = Closeness.logarithmic(500, 10, 1000) - Closeness.logarithmic(1000, 10, 1000);
    assertEquals(half, Closeness.logarithmic(50, 10, 1000) - Closeness.logarithmic(100, 10, 1000), 1e-9);
  }

  @Test
  void aFarEdgeInsideTheNearEdgeIsAStep() {
    assertEquals(1.0, Closeness.logarithmic(20, 20, 10));
    assertEquals(0.0, Closeness.logarithmic(21, 20, 10));
  }
}
