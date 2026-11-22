package com.tcrrry.diary
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class DiaryUpdatesTest {
 @Test fun onlyNewerFormalVersionIsAccepted(){
  assertTrue(DiaryUpdateRules.newer("1.10","1.2"));assertFalse(DiaryUpdateRules.newer("1.2.0","1.2"));assertEquals("1.3",DiaryUpdateRules.version("v1.3"));assertNull(DiaryUpdateRules.version("v1.3-preview.1"));assertNull(DiaryUpdateRules.version("1.3"))
  assertEquals("v1.3",DiaryUpdateRules.parse("""{"tag_name":"v1.3","draft":false,"prerelease":false}"""))
  assertThrows(IllegalArgumentException::class.java){DiaryUpdateRules.parse("""{"tag_name":"v1.3","prerelease":true}""")}
 }
 @Test fun reminderCanBeIgnoredSnoozedOrTurnedOff(){
  assertTrue(DiaryUpdateRules.shouldNotify(true,"v1.3",null,null,0,100))
  assertFalse(DiaryUpdateRules.shouldNotify(false,"v1.3",null,null,0,100))
  assertFalse(DiaryUpdateRules.shouldNotify(true,"v1.3","v1.3",null,0,100))
  assertFalse(DiaryUpdateRules.shouldNotify(true,"v1.3",null,"v1.3",200,100))
  assertTrue(DiaryUpdateRules.shouldNotify(true,"v1.3",null,"v1.3",200,200))
  assertTrue(DiaryUpdateRules.shouldNotify(true,"v1.4","v1.3","v1.3",0,100))
 }
 @Test fun releaseRedirectOnlyAcceptsFormalTagsFromThisRepository(){
  assertEquals("v1.3",DiaryUpdateRules.redirectTag("https://github.com/tcrrry/Tcrrry-Listening-Diary/releases/tag/v1.3"))
  assertEquals("v1.4",DiaryUpdateRules.redirectTag("/tcrrry/Tcrrry-Listening-Diary/releases/tag/v1.4"))
  for(url in listOf("https://evil.example/tcrrry/Tcrrry-Listening-Diary/releases/tag/v1.3","//evil.example/tcrrry/Tcrrry-Listening-Diary/releases/tag/v1.3","https://github.com/other/repo/releases/tag/v1.3","https://github.com/tcrrry/Tcrrry-Listening-Diary/releases/tag/v1.3-preview.1","https://github.com/tcrrry/Tcrrry-Listening-Diary/releases/tag/v1.3?x=1"))assertNull(DiaryUpdateRules.redirectTag(url))
 }
}
