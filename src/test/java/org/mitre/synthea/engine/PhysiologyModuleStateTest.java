package org.mitre.synthea.engine;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mitre.synthea.helpers.TimeSeriesData;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.VitalSign;

/**
 * Integration tests for the physiology "state path" wearables/digital-twin modules added in
 * PR #1347. Each of these modules contains a {@link State.Physiology} state that is a no-op
 * unless {@code physiology.state.enabled} is true, so these tests explicitly enable the flag,
 * load the production module fresh (so the SBML model is parsed and the solver is set up), drive
 * a Person through the Physiology state, and assert that the simulation actually runs and
 * populates its output attribute.
 *
 * <p>This guards the exact failure mode that shaped the PR's history: {@code Physiology.process()}
 * catches a {@code DerivativeException} and silently returns without setting the output attribute
 * (see State.java), so a model that fails to solve would leave the attribute null rather than
 * throwing. Asserting the attribute is populated proves the model both loads and solves.
 */
public class PhysiologyModuleStateTest {

  private Person person;
  private long time;
  private boolean physStateEnabled;

  /**
   * Enable the physiology state and build a minimal Person with the inputs the modules need.
   */
  @Before
  public void setup() {
    time = System.currentTimeMillis();

    person = new Person(0L);
    person.attributes.put(Person.GENDER, "F");
    long birthTime = time - org.mitre.synthea.helpers.Utilities.convertTime("years", 35);
    person.attributes.put(Person.BIRTHDATE, birthTime);
    // The cardiac / sleep-apnea inputs read #{BMI}; give the person a normal-range value.
    person.setVitalSign(VitalSign.BMI, 25.0);

    physStateEnabled = State.ENABLE_PHYSIOLOGY_STATE;
    State.ENABLE_PHYSIOLOGY_STATE = true;
  }

  @After
  public void tearDown() {
    State.ENABLE_PHYSIOLOGY_STATE = physStateEnabled;
  }

  /**
   * Loads a production module fresh (bypassing the static cache) so its Physiology states are set
   * up with the flag enabled, then returns the named state after running its predecessor setup.
   */
  private State loadState(String moduleFile, String stateName) throws Exception {
    Path modulePath = Paths.get("modules").resolve(moduleFile);
    Module module = Module.loadFile(modulePath, false, null, false);
    State state = module.getState(stateName);
    assertNotNull("Module " + moduleFile + " is missing state " + stateName, state);
    return state;
  }

  /**
   * Runs the Physiology state and asserts its from_list output attribute is populated with a
   * non-empty sampled time series (i.e. the simulation actually executed).
   */
  private void assertSimulationPopulates(String moduleFile, String stateName, String attribute)
      throws Exception {
    State physiology = loadState(moduleFile, stateName);

    boolean processed = physiology.process(person, time);
    assertTrue(stateName + " should return true from process()", processed);

    Object result = person.attributes.get(attribute);
    assertNotNull("Simulation " + stateName + " did not populate attribute \"" + attribute
        + "\" — the model likely failed to load or solve", result);
    assertTrue("Attribute \"" + attribute + "\" should be sampled TimeSeriesData",
        result instanceof TimeSeriesData);
    List<Double> series = ((TimeSeriesData) result).getValues();
    assertFalse("Simulation " + stateName + " produced an empty time series", series.isEmpty());
  }

  @Test
  public void endometriosisMenstrualCycleSimulationRuns() throws Exception {
    // Endocrine, ~1/month time scale: Roblitz2013 menstrual-cycle model -> estradiol (E2).
    assertSimulationPopulates("endometriosis.json", "Endocrinology_Sim", "endocrinology_data");
  }

  @Test
  public void cardiacEcgSimulationRuns() throws Exception {
    // Cardiac, ~1/second time scale: McSharry2003 synthetic ECG -> waveform (zf).
    assertSimulationPopulates(
        "wearables_cardiac_monitoring.json", "ECG_Sim", "ecg_result");
  }

  @Test
  public void sleepApneaCircadianSimulationRuns() throws Exception {
    // Circadian, ~1/day time scale: Hong2009 circadian clock -> clock protein (CPtot).
    assertSimulationPopulates(
        "wearables_sleepapnea.json", "CircadianClock_Sim", "sleep_monitor_data");
  }

  @Test
  public void sleepApneaOxygenIntakeSimulationRuns() throws Exception {
    // Pulmonary, ~1/minute time scale: Guyton1972 pulmonary O2 intake -> arterial pO2 (PO2ART).
    assertSimulationPopulates("wearables_sleepapnea.json", "O2_Sim", "o2_result");
  }
}
