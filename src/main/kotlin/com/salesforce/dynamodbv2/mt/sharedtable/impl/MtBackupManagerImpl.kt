/* Copyright (c) 2019, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause.
 * For full license text, see LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause.
 */
package com.salesforce.dynamodbv2.mt.sharedtable.impl

import com.amazonaws.services.dynamodbv2.model.AttributeValue
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest
import com.amazonaws.services.dynamodbv2.model.ScanRequest
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.AmazonS3Exception
import com.amazonaws.services.s3.model.DeleteObjectRequest
import com.amazonaws.services.s3.model.DeleteObjectsRequest
import com.amazonaws.services.s3.model.ListObjectsV2Request
import com.amazonaws.services.s3.model.ListObjectsV2Result
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.PutObjectRequest
import com.amazonaws.services.s3.model.S3Object
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Lists
import com.google.common.collect.Multimap
import com.google.common.collect.MultimapBuilder
import com.google.common.collect.Sets
import com.google.gson.ExclusionStrategy
import com.google.gson.FieldAttributes
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.salesforce.dynamodbv2.mt.context.MtAmazonDynamoDbContextProvider
import com.salesforce.dynamodbv2.mt.mappers.MtAmazonDynamoDbBase
import com.salesforce.dynamodbv2.mt.mappers.sharedtable.impl.MtAmazonDynamoDbBySharedTable
import com.salesforce.dynamodbv2.mt.sharedtable.CreateMtBackupRequest
import com.salesforce.dynamodbv2.mt.sharedtable.MtBackupManager
import com.salesforce.dynamodbv2.mt.sharedtable.MtBackupMetadata
import com.salesforce.dynamodbv2.mt.sharedtable.RestoreMtBackupRequest
import com.salesforce.dynamodbv2.mt.sharedtable.Status
import com.salesforce.dynamodbv2.mt.sharedtable.TenantRestoreMetadata
import com.salesforce.dynamodbv2.mt.sharedtable.TenantTableBackupMetadata
import org.slf4j.LoggerFactory
import java.nio.charset.Charset
import java.util.stream.Collectors

/**
 * Take an mt-dynamo set of tables, and using relatively naive scans across each table, build a set of backups
 * per tenant.
 */
open class MtBackupManagerImpl(region: String, val s3BucketName: String) : MtBackupManager {

    private val logger = LoggerFactory.getLogger(MtBackupManagerImpl::class.java)
    val s3: AmazonS3 = AmazonS3ClientBuilder.standard().withRegion(region).build()

    /**
     * At the moment, a naive (json) serializer is used to write and read backup snapshots.
     * This is wasteful in terms of space, but gets the job done.
     * Given CreateTableRequest objects are serialized to save table-tenant metadata, certain fields within
     * said object need to be filtered for GSON to deserialize the json back to a CreateTableRequest.
     *
     * Eventually, this should be replaced with a smarter [de]serialization strategy that is not as wasteful
     * in backup space cost.
     */
    val gson: Gson = GsonBuilder()
            .addDeserializationExclusionStrategy(object : ExclusionStrategy {
                override fun shouldSkipClass(clazz: Class<*>): Boolean {
                    return ignoreClassesOfCreateTableRequest.contains(clazz.name)
                }

                override fun shouldSkipField(field: FieldAttributes): Boolean {
                    return false
                }
            }).create()
    val ignoreClassesOfCreateTableRequest: Set<String> = ImmutableSet.of(
            "com.amazonaws.RequestClientOptions",
            "com.amazonaws.event.ProgressListener"
    )
    val charset = Charset.forName("utf-8")

    /**
     * Go through the shared data table and dump full row dumps into S3, segregated by tenant-table.
     *
     * Format on S3 looks like:
     *
     * ${backupBucket}/backupMetadata/metadata.json <- Overview of full backup and what is contained
     * ${backupBucket}/backupMetadata/${tenant1}/${virtTable1}-metadata.json <- CreateTableRequest tenant1-virtTable1
     * ${backupBucket}/backups/${tenant1}/${virtTable1}-${scanCount}.json
     * ${backupBucket}/backups/${tenant1}/${virtTable2}-${scanCount}.json
     * ...
     * ${backupBucket}/backups/${tenantN}/${virtTableN}-${N}}.json
     *
     * High level, the multitenant shared table is being scanned, and with each page of results, we write a file per
     * tenant-table onto S3 organized such that restoring a single tenant-table is a simple S3 directory listFiles
     * operation providing full row dumps to insert|restore back into dynamo under a different tenant-table space.
     *
     * TODO: Make this an async operation that properly uses an on-demand dynamo backup/restored table to run scans
     * against with a callback hook to monitor progress and wait for backup completion like the existing dynamo backup
     * APIs. For now, this is a synchronous call that operates the scan against a live table that may be taking writes,
     * so proceed with caution.
     */
    override fun createMtBackup(createMtBackupRequest: CreateMtBackupRequest, mtDynamo: MtAmazonDynamoDbBySharedTable): MtBackupMetadata {
        val startTime = System.currentTimeMillis()
        // write out table metadata
        val tenantMetadataMap: Map<TenantTable, CreateTableRequest> = mtDynamo.mtTableDescriptionRepo.allMtTables
        commitTenantTableMetadata(createMtBackupRequest.backupId, tenantMetadataMap)

        val backupMetadata = createBackupData(createMtBackupRequest, mtDynamo)
        // write out actual backup data
        commitBackupMetadata(backupMetadata)
        logger.info("${createMtBackupRequest.backupId}: Finished generating backup for ${tenantMetadataMap.size} " +
                "table-tenants in ${System.currentTimeMillis() - startTime} ms")
        return backupMetadata
    }

    /**
     * Pull down the backup for the given tenant-table snapshot and do the following:
     *  - Issue a CreateTable command with the metadata of the tenant-table snapshot to the new tenant-table context
     *  - Go through each backup file, and insert each row serially into mt-dynamo with the new tenant-table context
     *
     *  There's a lot of room for improvement here, esp in parallelizing/bulkifying the restore operation.
     */
    override fun restoreTenantTableBackup(
        restoreMtBackupRequest: RestoreMtBackupRequest,
        mtDynamo: MtAmazonDynamoDbBase,
        mtContext: MtAmazonDynamoDbContextProvider
    ): TenantRestoreMetadata {
        val startTime = System.currentTimeMillis()
        // restore tenant-table metadata
        val createTableReq: CreateTableRequest = getTenantTableMetadata(restoreMtBackupRequest.backupId, restoreMtBackupRequest.tenantTableBackup)
        mtContext.withContext(restoreMtBackupRequest.newTenantTable.tenantName) {
            mtDynamo.createTable(createTableReq.withTableName(restoreMtBackupRequest.newTenantTable.virtualTable))
        }

        // restore tenant-table data
        val backupFileKeys = getBackupFileKeys(restoreMtBackupRequest.backupId, restoreMtBackupRequest.tenantTableBackup)
        for (fileName in backupFileKeys) {
            val backupFile: S3Object = s3.getObject(s3BucketName, fileName)
            val rowsToInsert: List<TenantTableRow> = gson.fromJson(backupFile.objectContent.bufferedReader(),
                    object : TypeToken<List<TenantTableRow>>() {}.type)
            for (row in rowsToInsert) {
                mtContext.withContext(restoreMtBackupRequest.newTenantTable.tenantName) {
                    mtDynamo.putItem(restoreMtBackupRequest.newTenantTable.virtualTable, row.attributeMap)
                }
            }
        }
        logger.info("${restoreMtBackupRequest.backupId}: Finished restoring ${restoreMtBackupRequest.tenantTableBackup}" +
                " to ${restoreMtBackupRequest.newTenantTable} in ${System.currentTimeMillis() - startTime} ms")

        return TenantRestoreMetadata(restoreMtBackupRequest.backupId,
                Status.COMPLETE,
                restoreMtBackupRequest.newTenantTable.tenantName,
                restoreMtBackupRequest.newTenantTable.virtualTable)
    }

    override fun getTenantTableBackup(id: String): TenantTableBackupMetadata {
        TODO("not implemented")
    }

    private fun getTenantTableMetadata(backupId: String, tenantTable: TenantTable): CreateTableRequest {
        val tenantTableMetadataS3Location = getTenantTableMetadataFile(backupId, tenantTable)
        val tenantTableMetadataFile: S3Object = s3.getObject(s3BucketName, tenantTableMetadataS3Location)
        return gson.fromJson<CreateTableRequest>(tenantTableMetadataFile.objectContent.bufferedReader(),
                CreateTableRequest::class.java)
    }

    private fun getBackupFileKeys(backupId: String, tenantTable: TenantTable): Set<String> {
        val ret = Sets.newHashSet<String>()
        var continuationToken: String? = null
        do {
            val listBucketResult: ListObjectsV2Result = s3.listObjectsV2(
                    ListObjectsV2Request()
                            .withBucketName(s3BucketName)
                            .withContinuationToken(continuationToken)
                            .withPrefix("$backupDir/$backupId/${tenantTable.tenantName}/${tenantTable.virtualTable}"))
            continuationToken = listBucketResult.continuationToken
            for (o in listBucketResult.objectSummaries) {
                ret.add(o.key)
            }
        } while (listBucketResult.isTruncated)
        return ret
    }

    override fun listMtBackups(): List<MtBackupMetadata> {
        val ret = Lists.newArrayList<MtBackupMetadata>()
        var continuationToken: String? = null
        do {
            val listBucketResult: ListObjectsV2Result = s3.listObjectsV2(
                    ListObjectsV2Request()
                            .withDelimiter("/")
                            .withBucketName(s3BucketName)
                            .withContinuationToken(continuationToken)
                            .withPrefix(backupMetadataDir + "/"))
            continuationToken = listBucketResult.continuationToken
            for (o in listBucketResult.objectSummaries) {
                val backup = getBackup(getBackupIdFromKey(o.key))
                ret.add(backup)
            }
        } while (listBucketResult.isTruncated)
        return ret
    }

    override fun getBackup(id: String): MtBackupMetadata? {
        try {
            val backupFile: S3Object = s3.getObject(s3BucketName, getBackupMetadataFile(id))
            return gson.fromJson<MtBackupMetadata>(backupFile.objectContent.bufferedReader(), MtBackupMetadata::class.java)
        } catch (e: AmazonS3Exception) {
            if ("NoSuchKey".equals(e.errorCode)) {
                return null
            } else {
                throw e
            }
        }
    }

    override fun deleteBackup(id: String): MtBackupMetadata? {
        var continuationToken: String? = null
        var deleteCount = 0
        do {
            val listBucketResult: ListObjectsV2Result = s3.listObjectsV2(
                    ListObjectsV2Request()
                            .withBucketName(s3BucketName)
                            .withContinuationToken(continuationToken)
                            .withPrefix("$backupDir/$id/"))
            continuationToken = listBucketResult.continuationToken
            if (listBucketResult.objectSummaries.size > 0) {
                deleteCount += listBucketResult.objectSummaries.size
                s3.deleteObjects(DeleteObjectsRequest(s3BucketName).withKeys(
                        listBucketResult.objectSummaries.stream()
                                .map { DeleteObjectsRequest.KeyVersion(it.key) }
                                .collect(Collectors.toList()) as List<DeleteObjectsRequest.KeyVersion>).withQuiet(true))
            }
        } while (listBucketResult.isTruncated)
        logger.info("$id: Deleted $deleteCount backup files for $id")
        val ret = getBackup(id)
        s3.deleteObject(DeleteObjectRequest(s3BucketName, getBackupMetadataFile(id)))
        return ret
    }

    private fun commitTenantTableMetadata(backupId: String, tenantTableMetadataMap: Map<TenantTable, CreateTableRequest>) {
        for (tenantTable in tenantTableMetadataMap.keys) {
            val tenantTableMetadata = gson.toJson(tenantTableMetadataMap.get(tenantTable)).toByteArray(charset)
            val objectMetadata = ObjectMetadata()
            objectMetadata.contentLength = tenantTableMetadata.size.toLong()
            objectMetadata.contentType = "application/json"
            val putObjectReq = PutObjectRequest(s3BucketName, getTenantTableMetadataFile(backupId, tenantTable),
                    gson.toJson(tenantTableMetadataMap.get(tenantTable)).byteInputStream(charset), objectMetadata)
            s3.putObject(putObjectReq)
        }
    }

    protected fun commitBackupMetadata(backupMetadata: MtBackupMetadata) {
        // write out metadata of what was backed up
        val backupMetadataBytes = gson.toJson(backupMetadata).toByteArray(charset)
        val objectMetadata = ObjectMetadata()
        objectMetadata.contentLength = backupMetadataBytes.size.toLong()
        objectMetadata.contentType = "application/json"
        val putObjectReq = PutObjectRequest(s3BucketName, getBackupMetadataFile(backupMetadata.mtBackupId),
                gson.toJson(backupMetadata).byteInputStream(charset), objectMetadata)
        s3.putObject(putObjectReq)
    }

    protected open fun createBackupData(
        createMtBackupRequest: CreateMtBackupRequest,
        mtDynamo: MtAmazonDynamoDbBase
    ): MtBackupMetadata {
        val sharedTable = createMtBackupRequest.sharedTableName
        var lastRow: TenantTableRow? = null
        val tenantTables = Sets.newHashSet<TenantTable>()
        var numPasses = 0
        do {
            val scanRequest: ScanRequest = ScanRequest(sharedTable)
                    .withExclusiveStartKey(lastRow?.attributeMap)
            val scanResult = mtDynamo.scan(scanRequest)
            if (scanResult.lastEvaluatedKey != null) {
                lastRow = TenantTableRow(scanResult.lastEvaluatedKey)
            }

            val rowsPerTenant = MultimapBuilder.ListMultimapBuilder
                    .linkedHashKeys().hashSetValues().build<TenantTable, TenantTableRow>()
            for (i in 0..(scanResult.items.size - 1)) {
                val row = scanResult.items[i]
                val tenant = row.get(MtAmazonDynamoDbBase.TENANT_KEY)!!.s
                val virtualTable = row.remove(MtAmazonDynamoDbBase.VIRTUAL_TABLE_KEY)!!.s
                rowsPerTenant.put(TenantTable(tenantName = tenant, virtualTable = virtualTable),
                        TenantTableRow(scanResult.items[i]))
            }

            logger.info("Flushed ${rowsPerTenant.values().size} rows for " +
                    "${rowsPerTenant.keySet().size} tenants.")
            tenantTables.addAll(rowsPerTenant.keySet())
            flushScanResult(createMtBackupRequest.backupId, numPasses, rowsPerTenant)
        } while (scanResult.count > 0 && ++numPasses > 1)
        val tenantTableBackupMetadatas = tenantTables
                .stream()
                .map { TenantTableBackupMetadata(createMtBackupRequest.backupId,
                        Status.COMPLETE, it.tenantName, it.virtualTable,
                        getBackupFileKeys(createMtBackupRequest.backupId, it)) }
                .collect(Collectors.toSet())
        return MtBackupMetadata(createMtBackupRequest.backupId, Status.COMPLETE,
                tenantTableBackupMetadatas)
    }

    private val backupMetadataDir = "backupMetadata"
    private val backupDir = "backups"

    private fun getBackupMetadataFile(backupId: String) = "$backupMetadataDir/$backupId-metadata.json"
    private fun getBackupFile(backupId: String, tenantTable: TenantTable, scanCount: Int) =
            "$backupDir/$backupId/${tenantTable.tenantName}/${tenantTable.virtualTable}-$scanCount.json"
    private fun getTenantTableMetadataFile(backupId: String, tenantTable: TenantTable) =
            "$backupMetadataDir/$backupId/${tenantTable.tenantName}/${tenantTable.virtualTable}-metadata.json"

    private fun getBackupIdFromKey(backupMetadataKey: String) = backupMetadataKey.split("/")[1].split("-metadata")[0]

    private fun flushScanResult(backupId: String, scanCount: Int, rowsPerTenant: Multimap<TenantTable, TenantTableRow>) {
        for (tenantTable: TenantTable in rowsPerTenant.keySet()) {
            val toFlush = gson.toJson(rowsPerTenant.get(tenantTable))
            val objectMetadata = ObjectMetadata()
            objectMetadata.contentLength = toFlush.toByteArray(charset).size.toLong()
            objectMetadata.contentType = "application/json"
            val putObjectReq = PutObjectRequest(s3BucketName, getBackupFile(backupId, tenantTable, scanCount),
                    toFlush.byteInputStream(charset), objectMetadata)
            s3.putObject(putObjectReq)
        }
    }
}

data class TenantTable(val virtualTable: String, val tenantName: String)
data class TenantTableRow(val attributeMap: Map<String, AttributeValue>)