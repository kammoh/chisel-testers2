package chiseltest.legacy.backends.verilator

import chisel3.Module
import scala.sys.process._

/** Generates the Module specific verilator harness cpp file for verilator compilation
  */
object VerilatorCppHarnessGenerator {
  // example version string: Verilator 4.038 2020-07-11 rev v4.038
  lazy val verilatorVersion: (Int, Int) = { // (major, minor)
    val versionSplitted = "verilator --version".!!.trim().split(' ')
    assert(
      versionSplitted.length > 1 && versionSplitted.head == "Verilator",
      s"Unknown verilator version string: ${versionSplitted.mkString(" ")}"
    )
    val Array(maj, min) = versionSplitted(1).split('.').map(_.toInt)
    println(s"Detected Verilator version $maj.$min")
    (maj, min)
  }
  def codeGen(dut: Module, vcdFilePath: String, targetDir: String): String = {
    val codeBuffer = new StringBuilder

    def pushBack(vector: String, pathName: String, width: BigInt) {
      if (width == 0) {
        // Do nothing- 0 width wires are removed
      } else if (width <= 8) {
        codeBuffer.append(
          s"        sim_data.$vector.push_back(new VerilatorCData(&($pathName)));\n"
        )
      } else if (width <= 16) {
        codeBuffer.append(
          s"        sim_data.$vector.push_back(new VerilatorSData(&($pathName)));\n"
        )
      } else if (width <= 32) {
        codeBuffer.append(
          s"        sim_data.$vector.push_back(new VerilatorIData(&($pathName)));\n"
        )
      } else if (width <= 64) {
        codeBuffer.append(
          s"        sim_data.$vector.push_back(new VerilatorQData(&($pathName)));\n"
        )
      } else {
        val numWords = (width - 1) / 32 + 1
        codeBuffer.append(
          s"        sim_data.$vector.push_back(new VerilatorWData($pathName, $numWords));\n"
        )
      }
    }

    val (inputs, outputs) = getPorts(dut, "->")
    val dutName = dut.name
    val dutApiClassName = dutName + "_api_t"
    val dutVerilatorClassName = "V" + dutName
    val (verilatorMajor, verilatorMinor) = verilatorVersion

    val coverageInit =
      if (verilatorMajor >= 4 && verilatorMinor >= 202)
        """|Verilated::defaultContextp()->coveragep()->forcePerInstance(true);
           |""".stripMargin
      else ""

    val verilatorRunFlushCallback = if (verilatorMajor >= 4 && verilatorMinor >= 38) {
      "Verilated::runFlushCallbacks();\nVerilated::runExitCallbacks();\n"
    } else {
      "Verilated::flushCall();\n"
    }
    codeBuffer.append(s"""
#include "$dutVerilatorClassName.h"
#include "verilated.h"
#include "veri_api.h"
#if VM_TRACE
#include "verilated_vcd_c.h"
#endif
#include <iostream>

// Legacy function required only so linking works on Cygwin, MSVC++, and macOS
double sc_time_stamp() { return 0; }

#define HALF_PERIOD 5
class DutApi: public sim_api_t<VerilatorDataWrapper*> {
    public:
    DutApi($dutVerilatorClassName* _dut) {
        dut = _dut;
        contextp = _dut->contextp();
        is_exit = false;
#if VM_TRACE
        tfp = NULL;
#endif
    }
    void init_sim_data() {
        sim_data.inputs.clear();
        sim_data.outputs.clear();
        sim_data.signals.clear();

""")
    inputs.toList.foreach { case (node, name) =>
      // replaceFirst used here in case port name contains the dutName
      pushBack("inputs", name.replaceFirst(dutName, "dut"), node.getWidth)
    }
    outputs.toList.foreach { case (node, name) =>
      // replaceFirst used here in case port name contains the dutName
      pushBack("outputs", name.replaceFirst(dutName, "dut"), node.getWidth)
    }
    pushBack("signals", "dut->reset", 1)
    codeBuffer.append(
      s"""        sim_data.signal_map["${dut.reset.pathName}"] = 0;
    }
#if VM_TRACE
    void init_dump(VerilatedVcdC* _tfp) { tfp = _tfp; }
#endif
    inline bool exit() {
        return contextp->gotFinish() || is_exit;
    }

    private:
    $dutVerilatorClassName* dut;
    VerilatedContext* contextp;
    bool is_exit;
#if VM_TRACE
    VerilatedVcdC* tfp;
#endif
    virtual inline size_t put_value(VerilatorDataWrapper* &sig, uint64_t* data, bool force=false) {
        return sig->put_value(data);
    }
    virtual inline size_t get_value(VerilatorDataWrapper* &sig, uint64_t* data) {
        return sig->get_value(data);
    }
    virtual inline size_t get_chunk(VerilatorDataWrapper* &sig) {
        return sig->get_num_words();
    }
    virtual inline void reset() {
        dut->reset = 1;
        step();
    }
    virtual inline void start() {
        dut->reset = 0;
    }
    virtual inline void finish() {
        dut->eval();
        is_exit = true;
    }
    inline void half_step(bool rising) {
        dut->clock = rising;
        dut->eval();
#if VM_TRACE
        tfp->dump(contextp->time());
#endif
        contextp->timeInc(HALF_PERIOD);
    }
    virtual inline void step() {
        half_step(0);
        half_step(1);
    }
    virtual inline void update() {
        dut->eval();
    }
};

int main(int argc, char **argv, char **env) {
    VerilatedContext* contextp = new VerilatedContext;
    contextp->debug(0);
    contextp->randReset(2);
    contextp->commandArgs(argc, argv);
    $dutVerilatorClassName* top = new $dutVerilatorClassName{contextp, "TOP"};
#if VM_COVERAGE
    contextp->coveragep()->forcePerInstance(true);
#endif
#if VM_TRACE || VM_COVERAGE
    contextp->traceEverOn(true);
#endif
#if VM_TRACE
    VL_PRINTF("Enabling waves..\\n");
    VerilatedVcdC* tfp = new VerilatedVcdC;
    top->trace(tfp, 99);
    tfp->open("$vcdFilePath");
#endif
    DutApi api(top);
    api.init_sim_data();
    api.init_channels();
#if VM_TRACE
    api.init_dump(tfp);
#endif
    while(!api.exit()) {
      api.tick();
    }
    top->final();
#if VM_TRACE
    tfp->flush();
    tfp->close();
    delete tfp;
    tfp = nullptr;
#endif
#if VM_COVERAGE
    VL_PRINTF("Writing Coverage..\\n");
    Verilated::mkdir("$targetDir/logs");
    contextp->coveragep()->write("$targetDir/logs/coverage.dat");
#endif
    delete top;
    top = nullptr;
    delete contextp;
    contextp = nullptr;

    return 0;
}
"""
    )
    codeBuffer.toString()
  }
}
