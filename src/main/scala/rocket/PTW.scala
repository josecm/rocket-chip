// See LICENSE.Berkeley for license details.
// See LICENSE.SiFive for license details.

package freechips.rocketchip.rocket

import Chisel._
import Chisel.ImplicitConversions._
import chisel3.withClock
import chisel3.internal.sourceinfo.SourceInfo
import chisel3.experimental.chiselName
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.subsystem.CacheBlockBytes
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import freechips.rocketchip.util.property._
import scala.collection.mutable.ListBuffer

class PTWReq(implicit p: Parameters) extends CoreBundle()(p) {
  val addr = UInt(width = vpnBits)
  val priv_v = Bool()
  val do_stage1 = Bool()
  val do_stage2 = Bool()
}

class PTWResp(implicit p: Parameters) extends CoreBundle()(p) {
  val ae = Bool()
  val pte = new PTE
  val pte_s2 = new PTE
  val level = UInt(width = log2Ceil(pgLevels))
  val fragmented_superpage = Bool()
  val homogeneous = Bool()
}

class TLBPTWIO(implicit p: Parameters) extends CoreBundle()(p)
    with HasCoreParameters {
  val req = Decoupled(Valid(new PTWReq))
  val resp = Valid(new PTWResp).flip
  val ptbr = new PTBR().asInput
  val hptbr = new PTBR().asInput
  val gptbr = new PTBR().asInput
  val status = new MStatus().asInput
  val hstatus = new HStatus().asInput
  val gstatus = new MStatus().asInput
  val pmp = Vec(nPMPs, new PMP).asInput
  val customCSRs = coreParams.customCSRs.asInput
}

class PTWPerfEvents extends Bundle {
  val l2miss = Bool()
}

class DatapathPTWIO(implicit p: Parameters) extends CoreBundle()(p)
    with HasCoreParameters {
  val ptbr = new PTBR().asInput
  val hptbr = new PTBR().asInput
  val gptbr = new PTBR().asInput
  val sfence = Valid(new SFenceReq).flip
  val status = new MStatus().asInput
  val hstatus = new HStatus().asInput
  val gstatus = new MStatus().asInput
  val pmp = Vec(nPMPs, new PMP).asInput
  val perf = new PTWPerfEvents().asOutput
  val customCSRs = coreParams.customCSRs.asInput
  val clock_enabled = Bool(OUTPUT)
}

class PTE(implicit p: Parameters) extends CoreBundle()(p) {
  val ppn = UInt(width = 54)
  val reserved_for_software = Bits(width = 2)
  val d = Bool()
  val a = Bool()
  val g = Bool()
  val u = Bool()
  val x = Bool()
  val w = Bool()
  val r = Bool()
  val v = Bool()

  def table(dummy: Int = 0) = v && !r && !w && !x
  def leaf(dummy: Int = 0) = v && (r || (x && !w)) && a
  def ur(dummy: Int = 0) = sr() && u
  def uw(dummy: Int = 0) = sw() && u
  def ux(dummy: Int = 0) = sx() && u
  def sr(dummy: Int = 0) = leaf() && r
  def sw(dummy: Int = 0) = leaf() && w && d
  def sx(dummy: Int = 0) = leaf() && x
}

class L2TLBEntry(implicit p: Parameters) extends CoreBundle()(p)
    with HasCoreParameters {
  val idxBits = log2Ceil(coreParams.nL2TLBEntries)
  val tagBits = vpnBits - idxBits + { if(usingHype) 2 else 0 }
  val tag = UInt(width = tagBits)
  val ppn = UInt(width = ppnBits)
  val d = Bool()
  val a = Bool()
  val u = Bool()
  val x = Bool()
  val w = Bool()
  val r = Bool()
  val s2 = new PTE()

  override def cloneType = new L2TLBEntry().asInstanceOf[this.type]
}

@chiselName
class PTW(n: Int)(implicit edge: TLEdgeOut, p: Parameters) extends CoreModule()(p) {
  val io = new Bundle {
    val requestor = Vec(n, new TLBPTWIO).flip
    val mem = new HellaCacheIO
    val dpath = new DatapathPTWIO
  }

  val s_ready :: s_req :: s_switch :: s_wait1 :: s_dummy1 :: s_wait2 :: s_wait3 :: s_dummy2 :: s_fragment_superpage :: Nil = Enum(UInt(), 9)
  val state = Reg(init=s_ready)

  val arb = Module(new Arbiter(Valid(new PTWReq), n))
  arb.io.in <> io.requestor.map(_.req)
  arb.io.out.ready := state === s_ready

  val resp_valid = Reg(next = Vec.fill(io.requestor.size)(Bool(false)))

  val clock_en = state =/= s_ready || arb.io.out.valid || io.dpath.sfence.valid || io.dpath.customCSRs.disableDCacheClockGate
  io.dpath.clock_enabled := usingVM && clock_en
  val gated_clock =
    if (!usingVM || !tileParams.dcache.get.clockGate) clock
    else ClockGate(clock, clock_en, "ptw_clock_gate")
  withClock (gated_clock) { // entering gated-clock domain

  val invalidated = Reg(Bool())
  val count = Reg(UInt(width = log2Up(pgLevels)))
  val resp_ae = RegNext(false.B)
  val resp_fragmented_superpage = RegNext(false.B)

  val r_req = Reg(new PTWReq)
  val r_req_dest = Reg(Bits())
  val r_pte = Reg(new PTE)

  val priv_v = Bool(usingHype) & Mux(arb.io.out.fire(), arb.io.out.bits.bits.priv_v, r_req.priv_v)
  val do_stage2 = Bool(usingHype) & Mux(arb.io.out.fire(), arb.io.out.bits.bits.do_stage2, r_req.do_stage2)
  val do_stage1 = !Bool(usingHype) || Mux(arb.io.out.fire(), arb.io.out.bits.bits.do_stage1, r_req.do_stage1)
  val do_both_stages = do_stage1 & do_stage2
  val do_stage2_only = !do_stage1 & do_stage2
  val stage2 = RegInit(false.B)
  val s2_final = RegInit(false.B)
  val aux_count = Reg(UInt(width = log2Up(pgLevels)))
  val max_count = Mux(do_both_stages, count max aux_count, count)
  val aux_pte = Reg(new PTE)
  val vaddr = Mux(do_both_stages & stage2, aux_pte.ppn, r_req.addr)

  val mem_resp_valid = RegNext(io.mem.resp.valid)
  val mem_resp_data = RegNext(io.mem.resp.bits.data)
  io.mem.uncached_resp.map { resp =>
    assert(!(resp.valid && io.mem.resp.valid))
    resp.ready := true
    when (resp.valid) {
      mem_resp_valid := true
      mem_resp_data := resp.bits.data
    }
  }

  val (pte, invalid_paddr) = {
    val tmp = new PTE().fromBits(mem_resp_data)
    val res = Wire(init = tmp)
    res.ppn := Mux(do_both_stages && !stage2, tmp.ppn(vpnBits-1, 0), tmp.ppn(ppnBits-1, 0))
    when (tmp.r || tmp.w || tmp.x) {
      // for superpage mappings, make sure PPN LSBs are zero
      for (i <- 0 until pgLevels-1)
        when (count <= i && tmp.ppn((pgLevels-1-i)*pgLevelBits-1, (pgLevels-2-i)*pgLevelBits) =/= 0) { res.v := false }
    }
    (res, Mux(do_both_stages && !stage2, (tmp.ppn >> vpnBits), (tmp.ppn >> ppnBits)) =/= 0)
  }
  val traverse = pte.table() && !invalid_paddr && count < pgLevels-1
  val pte_addr = if (!usingVM) 0.U else 
    Mux(stage2 && count === 0.U, { 
      val vpn_idx = (vaddr >> (pgLevels-1)*pgLevelBits)(pgLevelBits-1+2,0)
      Cat(r_pte.ppn(r_pte.ppn.getWidth-1, 2), vpn_idx) << log2Ceil(xLen/8)
    }, {
      val vpn_idxs = (0 until pgLevels).map(i => (vaddr >> (pgLevels-i-1)*pgLevelBits)(pgLevelBits-1,0))
      val vpn_idx = vpn_idxs(count)
      Cat(r_pte.ppn, vpn_idx) << log2Ceil(xLen/8)
    })
  val pte_addr_s1 = Reg(init = pte_addr)
  
  //TODO: 2-stage stage translation is not supporting framgemented superpages
  val fragmented_superpage_ppn = {
    val choices = (pgLevels-1 until 0 by -1).map(i => Cat(r_pte.ppn >> (pgLevelBits*i), r_req.addr(((pgLevelBits*i) min vpnBits)-1, 0).padTo(pgLevelBits*i)))
    choices(count)
  }

  when (arb.io.out.fire()) {
    r_req := arb.io.out.bits.bits
    r_req_dest := arb.io.chosen
  }

  val (pte_cache_hit, pte_cache_data) = {
    val size = 1 << log2Up(pgLevels * 2)
    val plru = new PseudoLRU(size)
    val valid = RegInit(0.U(size.W))
    val tags = Reg(Vec(size, UInt(width = pte_addr.getWidth + { if(usingHype) 1 else 0 } )))
    val data = Reg(Vec(size, UInt(width = ppnBits)))
    val virtual_access = do_stage2 & !stage2
    val tagged_pte_addr = Cat(virtual_access, pte_addr)
    val refill_tagged_pte_addr = Mux(virtual_access, Cat(1.U, pte_addr_s1), Cat(0.U(1.W), pte_addr))

    val hits = tags.map(_ === tagged_pte_addr).asUInt & valid
    val hit = hits.orR
    when (mem_resp_valid && traverse && !hit && !invalidated) {
      val r = Mux(valid.andR, plru.replace, PriorityEncoder(~valid))
      valid := valid | UIntToOH(r)
      tags(r) := refill_tagged_pte_addr
      data(r) := pte.ppn
    }
    when (hit && (state === s_req || state === s_switch)) { plru.access(OHToUInt(hits)) }
    when (io.dpath.sfence.valid && !io.dpath.sfence.bits.rs1) { valid := 0.U }

    for (i <- 0 until pgLevels-1)
      ccover(hit && state === s_req && count === i, s"PTE_CACHE_HIT_L$i", s"PTE cache hit, level $i")

    (hit && count < pgLevels-1, Mux1H(hits, data))
  }

  val l2_refill = RegNext(false.B)
  io.dpath.perf.l2miss := false
  val (l2_hit, l2_error, l2_pte, l2_pte_s2, l2_tlb_ram) = if (coreParams.nL2TLBEntries == 0) (false.B, false.B, Wire(new PTE), Wire(new PTE), None) else {
    val code = new ParityCode
    require(isPow2(coreParams.nL2TLBEntries))
    val idxBits = log2Ceil(coreParams.nL2TLBEntries)

    val (ram, omSRAM) =  DescribedSRAM(
      name = "l2_tlb_ram",
      desc = "L2 TLB",
      size = coreParams.nL2TLBEntries,
      data = UInt(width = code.width(new L2TLBEntry().getWidth))
    )

    val g = Reg(UInt(width = coreParams.nL2TLBEntries))
    val virtual = Reg(UInt(width = coreParams.nL2TLBEntries))
    val valid = RegInit(UInt(0, coreParams.nL2TLBEntries))
    val (r_tag, r_idx) = Split(Cat(do_stage1, do_stage2, r_req.addr), idxBits)
    when (l2_refill && !invalidated) {
      val entry = Wire(new L2TLBEntry)
      entry := r_pte
      entry.s2 := aux_pte
      entry.tag := r_tag
      ram.write(r_idx, code.encode(entry.asUInt))

      val mask = UIntToOH(r_idx)
      valid := valid | mask
      g := Mux(r_pte.g, g | mask, g & ~mask)
      virtual := Mux(priv_v, virtual | mask, virtual & ~mask)
    }
    
    when (io.dpath.sfence.valid) {
      val virtual_match = ~(Fill(coreParams.nL2TLBEntries, io.dpath.sfence.bits.hg || io.dpath.sfence.bits.hv || priv_v) ^ virtual)
      valid :=
        Mux(io.dpath.sfence.bits.rs1, valid & ~(UIntToOH(io.dpath.sfence.bits.addr(idxBits+pgIdxBits-1, pgIdxBits)) & virtual_match),
        Mux(io.dpath.sfence.bits.rs2, valid & (g | ~virtual_match), 0.U))
    }

    val s0_valid = !l2_refill && arb.io.out.fire()
    val s1_valid = RegNext(s0_valid && arb.io.out.bits.valid)
    val s2_valid = RegNext(s1_valid)
    val s1_rdata = ram.read(arb.io.out.bits.bits.addr(idxBits-1, 0), s0_valid)
    val s2_rdata = code.decode(RegEnable(s1_rdata, s1_valid))
    val s2_valid_bit = RegEnable(valid(r_idx), s1_valid)
    val s2_g = RegEnable(g(r_idx), s1_valid)
    when (s2_valid && s2_valid_bit && s2_rdata.error) { valid := 0.U }

    val s2_entry = s2_rdata.uncorrected.asTypeOf(new L2TLBEntry)
    val s2_hit = s2_valid && s2_valid_bit && r_tag === s2_entry.tag
    io.dpath.perf.l2miss := s2_valid && !(s2_valid_bit && r_tag === s2_entry.tag)
    val s2_pte = Wire(new PTE)
    val s2_pte_s2 = Wire(new PTE)
    s2_pte := s2_entry
    s2_pte.g := s2_g
    s2_pte.v := true
    s2_pte_s2 := s2_entry.s2

    ccover(s2_hit, "L2_TLB_HIT", "L2 TLB hit")

    (s2_hit, s2_rdata.error, s2_pte, s2_pte_s2, Some(ram))
  }

  // if SFENCE occurs during walk, don't refill PTE cache or L2 TLB until next walk
  invalidated := io.dpath.sfence.valid || (invalidated && state =/= s_ready)

  io.mem.req.valid := state === s_req || state === s_dummy1
  io.mem.req.bits.phys := Bool(true)
  io.mem.req.bits.cmd  := M_XRD
  io.mem.req.bits.size := log2Ceil(xLen/8)
  io.mem.req.bits.signed := false
  io.mem.req.bits.addr := pte_addr
  io.mem.req.bits.dprv := PRV.S.U   // PTW accesses are S-mode by definition
  io.mem.s1_kill := l2_hit || state =/= s_wait1
  io.mem.s2_kill := Bool(false)

  val pageGranularityPMPs = pmpGranularity >= (1 << pgIdxBits)
  val pmaPgLevelHomogeneous = (0 until pgLevels) map { i =>
    val pgSize = BigInt(1) << (pgIdxBits + ((pgLevels - 1 - i) * pgLevelBits))
    if (pageGranularityPMPs && i == pgLevels - 1) {
      require(TLBPageLookup.homogeneous(edge.manager.managers, pgSize), s"All memory regions must be $pgSize-byte aligned")
      true.B
    } else {
      TLBPageLookup(edge.manager.managers, xLen, p(CacheBlockBytes), pgSize)(pte_addr).homogeneous
    }
  }
  val pmaHomogeneous = pmaPgLevelHomogeneous(count)
  val pmpHomogeneous = new PMPHomogeneityChecker(io.dpath.pmp).apply(pte_addr >> pgIdxBits << pgIdxBits, count)
  val homogeneous = pmaHomogeneous && pmpHomogeneous

  for (i <- 0 until io.requestor.size) {
    io.requestor(i).resp.valid := resp_valid(i)
    io.requestor(i).resp.bits.ae := resp_ae
    io.requestor(i).resp.bits.pte := r_pte
    io.requestor(i).resp.bits.pte_s2 := aux_pte
    io.requestor(i).resp.bits.level := max_count
    io.requestor(i).resp.bits.homogeneous := homogeneous || pageGranularityPMPs
    io.requestor(i).resp.bits.fragmented_superpage := resp_fragmented_superpage && pageGranularityPMPs
    io.requestor(i).ptbr := io.dpath.ptbr
    io.requestor(i).hptbr := io.dpath.hptbr
    io.requestor(i).gptbr := io.dpath.gptbr
    io.requestor(i).customCSRs := io.dpath.customCSRs
    io.requestor(i).status := io.dpath.status
    io.requestor(i).hstatus := io.dpath.hstatus
    io.requestor(i).gstatus := io.dpath.gstatus
    io.requestor(i).pmp := io.dpath.pmp
  }

  // control state machine
  val next_state = Wire(init = state)
  state := OptimizationBarrier(next_state)

  val fullPermPTE = {
      val pte = Wire(new PTE())
      pte.v := 1
      pte.r := 1
      pte.w := 1
      pte.x := 1     
      pte.u := 1  
      pte.g := 1 
      pte.a := 1 
      pte.d := 1 
      pte
  }

  def makePTE(ppn: UInt, default: PTE) = {
    val pte = Wire(init = default)
    pte.ppn := ppn
    pte
  }

  val ptbr = Mux(priv_v, io.dpath.gptbr, io.dpath.ptbr)

  switch (state) {
    is (s_ready) {
      assert(stage2 === false.B)  
      when (arb.io.out.fire()) {
        next_state := Mux(arb.io.out.bits.valid, Mux(!do_both_stages, s_req, Mux(do_stage2, s_switch, s_req)), s_ready)
        stage2 := do_stage2 && !do_stage1
        aux_pte := Mux(do_both_stages, new PTE().fromBits(0), fullPermPTE)
        aux_count := 0.U
      }
      count := pgLevels - minPgLevels - ptbr.additionalPgLevels
    }
    is (s_switch){
     assert(do_both_stages === true.B && stage2 === false.B)
     when (pte_cache_hit) {
       count := count + 1
     }.otherwise {
        aux_count := count
        count := pgLevels - minPgLevels - io.dpath.hptbr.additionalPgLevels
        aux_pte := Mux(!s2_final, r_pte, {
          val s1_ppns = (0 until pgLevels-1).map(i => Cat(r_pte.ppn(r_pte.ppn.getWidth-1, (pgLevels-i-1)*pgLevelBits), r_req.addr((pgLevels-i-1)*pgLevelBits-1,0))) :+ r_pte.ppn
          makePTE(s1_ppns(count), r_pte)
        })
        pte_addr_s1 := pte_addr
        stage2 := true.B
        next_state := s_req
      }        
    }
    is (s_req) {
      when (pte_cache_hit & (!do_stage2 | stage2)) { //the pte hit for s1 if followed by s2 is checked in s_switch
        count := count + 1
      }.otherwise {
        next_state := Mux(io.mem.req.ready, s_wait1, s_req)
      }
    }
    is (s_wait1) {
      // This Mux is for the l2_error case; the l2_hit && !l2_error case is overriden below
      // TODO: need to check if this works correclty with the extra s_switch state
      next_state := Mux(l2_hit, s_req, s_wait2)
    }
    is (s_wait2) {
      next_state := s_wait3
      when (io.mem.s2_xcpt.ae.ld) {
        resp_ae := true 
        next_state := s_ready
        resp_valid(r_req_dest) := true
      }
    }
    is (s_fragment_superpage) {
      next_state := s_ready
      resp_valid(r_req_dest) := true
      resp_ae := false
      when (!homogeneous) {
        count := pgLevels-1
        resp_fragmented_superpage := true
      }
    }
  }

  val superpage_masks = (0 until pgLevels).map(i => ~(((1 << ((pgLevels-1-i)*pgLevelBits)) - 1)).U(new PTE().ppn.getWidth.W))

  val merged_pte = {
    val s1_ppns = (0 until pgLevels-1).map(i => Cat(pte.ppn(pte.ppn.getWidth-1, (pgLevels-i-1)*pgLevelBits), aux_pte.ppn((pgLevels-i-1)*pgLevelBits-1,0))) :+ pte.ppn
    val ppn_tmp = Mux(s2_final, s1_ppns(count) & superpage_masks(max_count), s1_ppns(count))
    val pte_temp = makePTE(ppn_tmp, aux_pte)
    pte_temp
  }

  r_pte := OptimizationBarrier(
    Mux(mem_resp_valid & !traverse & do_both_stages & stage2, merged_pte,
    Mux(mem_resp_valid & !traverse & do_stage2_only, makePTE(pte.ppn, fullPermPTE),
    Mux(mem_resp_valid, pte,
    Mux(l2_hit && !l2_error, l2_pte,
    Mux(state === s_fragment_superpage && !homogeneous, makePTE(fragmented_superpage_ppn, r_pte),
    Mux((state === s_req || state === s_switch) && pte_cache_hit, makePTE(pte_cache_data, l2_pte),
    Mux(state === s_switch, makePTE(io.dpath.hptbr.ppn, r_pte),
    Mux(arb.io.out.fire(), Mux(do_stage1, makePTE(ptbr.ppn, r_pte), makePTE(io.dpath.hptbr.ppn, r_pte)),
    r_pte)))))))))

  when (l2_hit && !l2_error) {
    assert(state === s_req || state === s_switch || state === s_wait1)
    next_state := s_ready
    resp_valid(r_req_dest) := true
    resp_ae := false
    aux_pte := l2_pte_s2
    count := pgLevels-1
  }
  when (mem_resp_valid) {
    assert(state === s_wait3)
    when (traverse) {
      next_state := Mux(do_both_stages & !stage2, s_switch, s_req)
      count := count + 1
    }.elsewhen (do_both_stages & !s2_final) {
      when(stage2) {
        stage2 := false.B
        count := aux_count
        next_state := s_req
      }.otherwise {
        s2_final := true.B
        next_state := s_switch
      }
    }.otherwise {
      l2_refill := pte.v && !invalid_paddr && max_count === pgLevels-1
      val ae = pte.v && invalid_paddr
      resp_ae := ae
      count := max_count
      when (pageGranularityPMPs && max_count =/= pgLevels-1 && !ae) {
        next_state := s_fragment_superpage
      }.otherwise {
        next_state := s_ready
        resp_valid(r_req_dest) := true
      }
    }
  }

  when(Bool(usingHype) && mem_resp_valid && !traverse) {

    val ae = !stage2 && do_both_stages && !pte.v
    val gae = (!stage2 && do_both_stages && pte.v && invalid_paddr) || (stage2 && !s2_final && !pte.ur())

    when(s2_final) {
      aux_pte := makePTE(aux_pte.ppn & superpage_masks(max_count), pte)
    }

    when(do_stage2_only) {
      aux_pte := makePTE(r_req.addr & superpage_masks(max_count), pte)
    }

    when(gae) { 
      aux_pte.v := false.B 
    } 

    when(ae) {
      aux_pte := fullPermPTE
    }

    when(ae || gae) {
        next_state := s_ready
        resp_valid(r_req_dest) := true
    } 
  }

  when (io.mem.s2_nack) {
    assert(state === s_wait2)
    next_state := s_req
  }
  when(Bool(usingHype) && next_state === s_ready){
    stage2 := false.B
    s2_final := false.B
  }

  for (i <- 0 until pgLevels) {
    val leaf = mem_resp_valid && !traverse && count === i
    ccover(leaf && pte.v && !invalid_paddr, s"L$i", s"successful page-table access, level $i")
    ccover(leaf && pte.v && invalid_paddr, s"L${i}_BAD_PPN_MSB", s"PPN too large, level $i")
    ccover(leaf && !mem_resp_data(0), s"L${i}_INVALID_PTE", s"page not present, level $i")
    if (i != pgLevels-1)
      ccover(leaf && !pte.v && mem_resp_data(0), s"L${i}_BAD_PPN_LSB", s"PPN LSBs not zero, level $i")
  }
  ccover(mem_resp_valid && count === pgLevels-1 && pte.table(), s"TOO_DEEP", s"page table too deep")
  ccover(io.mem.s2_nack, "NACK", "D$ nacked page-table access")
  ccover(state === s_wait2 && io.mem.s2_xcpt.ae.ld, "AE", "access exception while walking page table")

  } // leaving gated-clock domain

  private def ccover(cond: Bool, label: String, desc: String)(implicit sourceInfo: SourceInfo) =
    if (usingVM) cover(cond, s"PTW_$label", "MemorySystem;;" + desc)
}

/** Mix-ins for constructing tiles that might have a PTW */
trait CanHavePTW extends HasTileParameters with HasHellaCache { this: BaseTile =>
  val module: CanHavePTWModule
  var nPTWPorts = 1
  nDCachePorts += usingPTW.toInt
}

trait CanHavePTWModule extends HasHellaCacheModule {
  val outer: CanHavePTW
  val ptwPorts = ListBuffer(outer.dcache.module.io.ptw)
  val ptw = Module(new PTW(outer.nPTWPorts)(outer.dcache.node.edges.out(0), outer.p))
  if (outer.usingPTW)
    dcachePorts += ptw.io.mem
}
