package com.hardwareshop.pos

import com.hardwareshop.pos.domain.model.Product
import com.hardwareshop.pos.util.*
import org.junit.Assert.*
import org.junit.Test

class JobToolsTest {
    private fun product(id:Long,name:String,size:String="1 inch",category:String="PVC",pipe:Boolean=false)=
        Product(id,1,category,1,name,name,size,"piece",10000,0,0,10.0,0.0,null,isActive=true,isPipe=pipe)
    @Test fun nothingIsSuggestedBeforeAnItemIsAdded(){assertTrue(JobSuggestions.build(listOf(product(2,"Elbow")),emptyList(),mapOf(2L to 10L)).isEmpty())}
    @Test fun anyCartProductCanUseActualBoughtTogetherHistory(){
        val cart=product(1,"Hammer",category="Tools")
        val other=product(2,"Nails",category="Fasteners")
        val result=JobSuggestions.build(listOf(cart,other),listOf(cart),mapOf(2L to 3L))
        assertEquals(other,result.single().product);assertTrue(result.single().reason.contains("3 saved bills"))
    }
    @Test fun wrongMaterialSizeInactiveAndOutOfStockAreExcluded(){
        val pipe=product(1,"PVC Pipe",pipe=true)
        val right=product(2,"PVC Elbow")
        val catalogue=listOf(pipe,right,product(3,"CPVC Elbow",category="CPVC"),product(4,"PVC Elbow","1/2 inch"),
            product(5,"PVC Socket").copy(currentStock=0.0),product(6,"PVC Socket").copy(isActive=false))
        assertEquals(listOf(2L),JobSuggestions.build(catalogue,listOf(pipe),emptyMap()).map { it.product.id })
    }
    @Test fun cartItemsAreNeverRepeatedInSuggestions(){
        val pipe=product(1,"PVC Pipe",pipe=true);val elbow=product(2,"PVC Elbow")
        assertTrue(JobSuggestions.build(listOf(pipe,elbow),listOf(pipe,elbow),mapOf(2L to 20L)).isEmpty())
    }
    @Test fun historyAndFittingHintsDeduplicate(){
        val pipe=product(1,"PVC Pipe",pipe=true);val elbow=product(2,"PVC Elbow")
        assertEquals(1,JobSuggestions.build(listOf(elbow,elbow),listOf(pipe),mapOf(2L to 2L)).size)
    }
    @Test fun solventIsMatchedByMaterialNotDiameter(){
        val pipe=product(1,"UPVC Pipe",category="UPVC",pipe=true)
        val solvent=product(2,"UPVC Solvent","100 ml","UPVC")
        assertEquals(solvent,JobSuggestions.build(listOf(solvent,product(3,"CPVC Solvent",category="CPVC")),listOf(pipe),emptyMap()).single().product)
    }
    @Test fun unknownMaterialsDoNotGuessFittingCompatibility(){
        val hints=JobSuggestions.build(listOf(product(2,"Elbow",category="General")),
            listOf(product(1,"Pipe",category="General",pipe=true)),emptyMap())
        assertTrue(hints.all { it.reason.contains("browse") })
    }
    @Test fun restoredCartUsesCurrentCatalogueMaterialLabels(){
        val labelled=product(1,"Pipe",category="CPVC",pipe=true)
        val elbow=product(2,"Elbow",category="CPVC")
        val hints=JobSuggestions.build(listOf(labelled,elbow),listOf(labelled.copy(categoryName="",typeName="")),emptyMap())
        assertTrue(hints.single().reason.contains("Same material and size"))
    }
    @Test fun twentyFeetCanSupplyEightSevenFiveWithoutWaste(){
        val plan=PipeCutPlanner.plan(1,PipeCutPlanner.requests("8,7,5"),emptyList())
        assertEquals(1,plan.newPipes);assertEquals(0,plan.sources.single().remaining)
    }
    @Test fun shortestSuitableExistingOffcutIsUsedFirst(){
        val pieces=listOf(PipeCutPlanner.Piece("large",1,1800),PipeCutPlanner.Piece("small",1,600))
        val plan=PipeCutPlanner.plan(1,listOf(500),pieces)
        assertEquals("small",plan.sources.single().piece?.id);assertEquals(100,plan.sources.single().remaining)
        assertEquals(0,plan.newPipes)
    }
    @Test fun otherProductOffcutsNeverGetUsed(){
        val plan=PipeCutPlanner.plan(1,listOf(500),listOf(PipeCutPlanner.Piece("wrong",2,500)))
        assertEquals(1,plan.newPipes)
    }
    @Test fun sawLossMayRequireAnotherPipe(){
        val plan=PipeCutPlanner.plan(1,listOf(1000,1000),emptyList(),1)
        assertEquals(2,plan.newPipes);assertEquals(999,plan.sources.first().remaining)
    }
    @Test fun exactEndFitDoesNotChargeAnotherSawCut(){
        val plan=PipeCutPlanner.plan(1,listOf(600),listOf(PipeCutPlanner.Piece("piece",1,600)),2)
        assertEquals(0,plan.sources.single().remaining)
    }
    @Test fun decimalLengthsRemainExact(){
        val plan=PipeCutPlanner.plan(1,PipeCutPlanner.requests("0.1, 0.2, 19.7"),emptyList())
        assertEquals(0,plan.sources.single().remaining);assertEquals("0.1",PipeCutPlanner.format(10))
    }
    @Test fun invalidRequestsAreRejected(){
        listOf("0","-1","NaN","20.01","1.001","8,,7","").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { PipeCutPlanner.requests(value) }
        }
    }
    @Test fun planNeverConsumesMoreThanAvailableIncludingLoss(){
        val plan=PipeCutPlanner.plan(1,(1..60).map { it%19*100+1 },listOf(PipeCutPlanner.Piece("a",1,1345)),3)
        plan.sources.forEach { source->
            assertTrue(source.remaining>=0)
            val loss=source.capacity-source.cuts.sum()-source.remaining
            assertTrue(loss>=0 && loss<=source.cuts.size*3)
        }
    }
    @Test fun duplicatePieceIdentifiersAreRejected(){
        assertThrows(IllegalArgumentException::class.java) { PipeCutPlanner.plan(1,listOf(500),
            listOf(PipeCutPlanner.Piece("same",1,500),PipeCutPlanner.Piece("same",1,600))) }
    }
}
