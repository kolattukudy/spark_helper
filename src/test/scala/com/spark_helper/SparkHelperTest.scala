package com.spark_helper

import com.spark_helper.SparkHelper.{RDDExtensions, PairRDDExtensions}
import com.spark_helper.SparkHelper.SparkContextExtensions

import org.apache.hadoop.io.compress.GzipCodec

import com.holdenkarau.spark.testing.{SharedSparkContext, RDDComparisons}

import org.scalatest.FunSuite

/** Testing facility for the SparkHelper.
  *
  * @author Xavier Guihot
  * @since 2017-02
  */
class SparkHelperTest
    extends FunSuite
    with SharedSparkContext
    with RDDComparisons {

  val resourceFolder = "src/test/resources"

  test("Save as single text file") {

    val testFolder = s"$resourceFolder/folder"
    val singleTextFilePath = s"$testFolder/single_text_file.txt"
    val tmpFolder = s"$resourceFolder/tmp"

    HdfsHelper.deleteFolder(testFolder)
    HdfsHelper.deleteFolder(tmpFolder)

    val rddToStore =
      sc.parallelize(Array("data_a", "data_b", "data_c")).repartition(3)

    // 1: Without an intermediate working dir:

    rddToStore.saveAsSingleTextFile(singleTextFilePath)

    var singleFileStoredData = sc.textFile(singleTextFilePath).collect().sorted

    assert(singleFileStoredData === Array("data_a", "data_b", "data_c"))

    HdfsHelper.deleteFolder(testFolder)

    // 2: With an intermediate working dir:
    // Notice as well that we test by moving the single file in a folder
    // which doesn't exists.

    rddToStore.saveAsSingleTextFile(
      singleTextFilePath,
      workingFolder = tmpFolder
    )

    singleFileStoredData = sc.textFile(singleTextFilePath).collect().sorted

    assert(singleFileStoredData === Array("data_a", "data_b", "data_c"))

    HdfsHelper.deleteFolder(testFolder)
    HdfsHelper.deleteFolder(tmpFolder)

    // 3: With a compression codec:

    rddToStore
      .saveAsSingleTextFile(s"$singleTextFilePath.gz", classOf[GzipCodec])

    singleFileStoredData =
      sc.textFile(s"$singleTextFilePath.gz").collect().sorted

    assert(singleFileStoredData === Array("data_a", "data_b", "data_c"))

    HdfsHelper.deleteFolder(testFolder)
  }

  test("Read text file with specific record delimiter") {

    val weirdFormatFilePath = s"$resourceFolder/some_weird_format.txt"

    // 1: Let's read a file where a record begins with a line begining with
    // 3 and other lines begining by 4:

    HdfsHelper.deleteFile(weirdFormatFilePath)

    val textContent = (
      "3 first line of the first record\n" +
        "4 another line of the first record\n" +
        "4 and another one for the first record\n" +
        "3 first line of the second record\n" +
        "3 first line of the third record\n" +
        "4 another line for the third record"
    )

    HdfsHelper.writeToHdfsFile(textContent, weirdFormatFilePath)

    var computedRecords = sc.textFile(weirdFormatFilePath, "\n3").collect()

    var expectedRecords = Array(
      (
        "3 first line of the first record\n" +
          "4 another line of the first record\n" +
          "4 and another one for the first record"
      ),
      " first line of the second record",
      (
        " first line of the third record\n" +
          "4 another line for the third record"
      )
    )

    assert(computedRecords === expectedRecords)

    HdfsHelper.deleteFile(weirdFormatFilePath)

    // 2: Let's read an xml file:

    val xmlFilePath = s"$resourceFolder/some_basic_xml.xml"

    HdfsHelper.deleteFile(xmlFilePath)

    val xmlTextContent =
      "<Customers>\n" +
        "<Customer>\n" +
        "<Address>34 thingy street, someplace, sometown</Address>\n" +
        "</Customer>\n" +
        "<Customer>\n" +
        "<Address>12 thingy street, someplace, sometown</Address>\n" +
        "</Customer>\n" +
        "</Customers>"

    HdfsHelper.writeToHdfsFile(xmlTextContent, xmlFilePath)

    computedRecords = sc.textFile(xmlFilePath, "<Customer>\n").collect()

    expectedRecords = Array(
      "<Customers>\n",
      (
        "<Address>34 thingy street, someplace, sometown</Address>\n" +
          "</Customer>\n"
      ),
      (
        "<Address>12 thingy street, someplace, sometown</Address>\n" +
          "</Customer>\n" +
          "</Customers>"
      )
    )

    assert(computedRecords === expectedRecords)

    HdfsHelper.deleteFile(xmlFilePath)
  }

  test("Save as text file by key") {

    val keyValueFolder = s"$resourceFolder/key_value_storage"

    // 1: Let's strore key values per file:

    HdfsHelper.deleteFolder(keyValueFolder)

    val someKeyValueRdd = sc.parallelize[(String, String)](
      Array(
        ("key_1", "value_a"),
        ("key_1", "value_b"),
        ("key_2", "value_c"),
        ("key_2", "value_b"),
        ("key_2", "value_d"),
        ("key_3", "value_a"),
        ("key_3", "value_b")
      )
    )

    someKeyValueRdd.saveAsTextFileByKey(keyValueFolder, 3)

    // The folder key_value_storage has been created:
    assert(HdfsHelper.folderExists(keyValueFolder))

    // And it contains one file per key:
    var genratedKeyFiles = HdfsHelper.listFileNamesInFolder(keyValueFolder)
    var expectedKeyFiles = List("_SUCCESS", "key_1", "key_2", "key_3")
    assert(genratedKeyFiles === expectedKeyFiles)

    var valuesForKey1 = sc.textFile(s"$keyValueFolder/key_1").collect().sorted
    assert(valuesForKey1 === Array("value_a", "value_b"))

    val valuesForKey2 = sc.textFile(s"$keyValueFolder/key_2").collect().sorted
    assert(valuesForKey2 === Array("value_b", "value_c", "value_d"))

    val valuesForKey3 = sc.textFile(s"$keyValueFolder/key_3").collect().sorted
    assert(valuesForKey3 === Array("value_a", "value_b"))

    // 2: Let's strore key values per file; but without providing the nbr of
    // keys:

    HdfsHelper.deleteFolder(keyValueFolder)

    someKeyValueRdd.saveAsTextFileByKey(keyValueFolder)

    // The folder key_value_storage has been created:
    assert(HdfsHelper.folderExists(keyValueFolder))

    // And it contains one file per key:
    genratedKeyFiles = HdfsHelper.listFileNamesInFolder(keyValueFolder)
    expectedKeyFiles = List("_SUCCESS", "key_1", "key_2", "key_3")
    assert(genratedKeyFiles === expectedKeyFiles)

    valuesForKey1 = sc.textFile(s"$keyValueFolder/key_1").collect().sorted
    assert(valuesForKey1 === Array("value_a", "value_b"))

    // 3: Let's strore key values per file and compress these files:

    HdfsHelper.deleteFolder(keyValueFolder)

    someKeyValueRdd.saveAsTextFileByKey(keyValueFolder, 3, classOf[GzipCodec])

    // The folder key_value_storage has been created:
    assert(HdfsHelper.folderExists(keyValueFolder))

    // And it contains one file per key:
    genratedKeyFiles = HdfsHelper.listFileNamesInFolder(keyValueFolder)
    expectedKeyFiles = List("_SUCCESS", "key_1.gz", "key_2.gz", "key_3.gz")
    assert(genratedKeyFiles === expectedKeyFiles)

    valuesForKey1 = sc.textFile(s"$keyValueFolder/key_1.gz").collect().sorted
    assert(valuesForKey1 === Array("value_a", "value_b"))

    HdfsHelper.deleteFolder(keyValueFolder)
  }

  test("Decrease coalescence level") {

    HdfsHelper.deleteFolder("src/test/resources/re_coalescence_test_input")
    HdfsHelper.deleteFolder("src/test/resources/re_coalescence_test_output")

    // Let's create the folder with high level of coalescence (3 files):
    sc.parallelize[String](Array("data_1_a", "data_1_b", "data_1_c"))
      .saveAsSingleTextFile(
        "src/test/resources/re_coalescence_test_input/input_file_1"
      )
    sc.parallelize[String](Array("data_2_a", "data_2_b"))
      .saveAsSingleTextFile(
        "src/test/resources/re_coalescence_test_input/input_file_2"
      )
    sc.parallelize[String](Array("data_3_a", "data_3_b", "data_3_c"))
      .saveAsSingleTextFile(
        "src/test/resources/re_coalescence_test_input/input_file_3"
      )

    // Let's decrease the coalescence level in order to only have 2 files:
    SparkHelper.decreaseCoalescence(
      "src/test/resources/re_coalescence_test_input",
      "src/test/resources/re_coalescence_test_output",
      2,
      sc)

    // And we check we have two files in output:
    val outputFileList = HdfsHelper
      .listFileNamesInFolder("src/test/resources/re_coalescence_test_output")
    val expectedFileList = List("_SUCCESS", "part-00000", "part-00001")
    assert(outputFileList === expectedFileList)

    // And that all input data is in the output:
    val outputData = sc
      .textFile("src/test/resources/re_coalescence_test_output")
      .collect
      .sorted

    val expectedOutputData = Array(
      "data_1_a",
      "data_1_b",
      "data_1_c",
      "data_2_a",
      "data_2_b",
      "data_3_a",
      "data_3_b",
      "data_3_c"
    )
    assert(outputData === expectedOutputData)

    HdfsHelper.deleteFolder("src/test/resources/re_coalescence_test_output")
  }

  test(
    "Extract lines of files to an RDD of tuple containing the line and file " +
      "the line comes from") {

    HdfsHelper.deleteFolder("src/test/resources/with_file_name")
    HdfsHelper.writeToHdfsFile(
      "data_1_a\ndata_1_b\ndata_1_c",
      "src/test/resources/with_file_name/file_1.txt")
    HdfsHelper.writeToHdfsFile(
      "data_2_a\ndata_2_b",
      "src/test/resources/with_file_name/file_2.txt")
    HdfsHelper.writeToHdfsFile(
      "data_3_a\ndata_3_b\ndata_3_c\ndata_3_d",
      "src/test/resources/with_file_name/folder_1/file_3.txt")

    val computedRdd = SparkHelper
      .textFileWithFileName("src/test/resources/with_file_name", sc)
      // We remove the part of the path which is specific to the local machine
      // on which the test run:
      .map {
        case (filePath, line) =>
          val nonLocalPath = filePath.split("src/test/") match {
            case Array(localPartOfPath, projectRelativePath) =>
              "file:/.../src/test/" + projectRelativePath
          }
          (nonLocalPath, line)
      }

    val expectedRDD = sc.parallelize(
      Array(
        ("file:/.../src/test/resources/with_file_name/file_1.txt", "data_1_a"),
        ("file:/.../src/test/resources/with_file_name/file_1.txt", "data_1_b"),
        ("file:/.../src/test/resources/with_file_name/file_1.txt", "data_1_c"),
        (
          "file:/.../src/test/resources/with_file_name/folder_1/file_3.txt",
          "data_3_a"),
        (
          "file:/.../src/test/resources/with_file_name/folder_1/file_3.txt",
          "data_3_b"),
        (
          "file:/.../src/test/resources/with_file_name/folder_1/file_3.txt",
          "data_3_c"),
        (
          "file:/.../src/test/resources/with_file_name/folder_1/file_3.txt",
          "data_3_d"),
        ("file:/.../src/test/resources/with_file_name/file_2.txt", "data_2_a"),
        ("file:/.../src/test/resources/with_file_name/file_2.txt", "data_2_b")
      ))

    assertRDDEquals(computedRdd, expectedRDD)

    HdfsHelper.deleteFolder("src/test/resources/with_file_name")
  }
}
