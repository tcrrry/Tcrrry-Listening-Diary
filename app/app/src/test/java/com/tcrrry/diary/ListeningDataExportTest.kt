package com.tcrrry.diary
import org.junit.Test
import org.junit.Assert.*
import java.io.StringWriter
class ListeningDataExportTest {
 @Test fun quotesCsvAndIncludesAccurateVisitDurationAndCount(){
  val p=Play("id","visit","A, \"song\"","Artist","Album",1000,61000,60000,180000,"UTC",effective=1)
  val out=StringWriter();writeListeningExport(out,DiaryReport(history=listOf(p)),true)
  assertTrue(out.toString().contains("\"A, \"\"song\"\"\""));assertTrue(out.toString().contains("\"60.0\""));assertTrue(out.toString().contains("\"1\""))
 }
}
