/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2017-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.blobstore.gcloud.internal

import java.nio.channels.FileChannel
import java.util.stream.Collectors
import java.util.stream.Stream

import org.sonatype.nexus.blobstore.BlobIdLocationResolver
import org.sonatype.nexus.blobstore.DefaultBlobIdLocationResolver
import org.sonatype.nexus.blobstore.MockBlobStoreConfiguration
import org.sonatype.nexus.blobstore.api.Blob
import org.sonatype.nexus.blobstore.api.BlobId
import org.sonatype.nexus.blobstore.api.BlobStore
import org.sonatype.nexus.blobstore.api.BlobStoreException
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaService
import org.sonatype.nexus.common.log.DryRunPrefix
import org.sonatype.nexus.scheduling.PeriodicJobService

import com.codahale.metrics.MetricRegistry
import com.google.api.gax.paging.Page
import com.google.cloud.datastore.Cursor
import com.google.cloud.datastore.Datastore
import com.google.cloud.datastore.Entity
import com.google.cloud.datastore.FullEntity
import com.google.cloud.datastore.Key
import com.google.cloud.datastore.KeyFactory
import com.google.cloud.datastore.QueryResults
import com.google.cloud.datastore.Transaction
import com.google.cloud.storage.Bucket
import com.google.cloud.storage.Storage
import com.google.cloud.storage.Storage.BlobListOption
import com.google.datastore.v1.QueryResultBatch.MoreResultsType
import org.apache.commons.io.IOUtils
import spock.lang.Specification

class GoogleCloudBlobStoreTest
  extends Specification
{

  GoogleCloudStorageFactory storageFactory = Mock()

  BlobIdLocationResolver blobIdLocationResolver = new DefaultBlobIdLocationResolver()

  PeriodicJobService periodicJobService = Mock()

  Storage storage = Mock()

  Bucket bucket = Mock()

  MetricRegistry metricRegistry = new MetricRegistry()

  GoogleCloudDatastoreFactory datastoreFactory = Mock()

  Datastore datastore = Mock()

  MultipartUploader uploader = new MultipartUploader(metricRegistry, 1024)

  BlobStoreQuotaService quotaService = Mock()

  KeyFactory keyFactory = new KeyFactory("testing")

  def blobHeaders = [
      (BlobStore.BLOB_NAME_HEADER): 'test',
      (BlobStore.CREATED_BY_HEADER): 'admin'
  ]
  GoogleCloudBlobStore blobStore = new GoogleCloudBlobStore(
      storageFactory, blobIdLocationResolver, periodicJobService, datastoreFactory, new DryRunPrefix("TEST "),
      uploader, metricRegistry, quotaService, 60)

  def config = new MockBlobStoreConfiguration()

  static File tempFileBytes
  static File tempFileAttributes
  static File fileMetadata
  static File otherMetadata

  def setupSpec() {
    tempFileBytes = File.createTempFile('gcloudtest', 'bytes')
    tempFileBytes << 'some blob contents'

    tempFileAttributes = File.createTempFile('gcloudtest', 'properties')
    tempFileAttributes << """\
        |#Thu Jun 01 23:10:55 UTC 2017
        |@BlobStore.created-by=admin
        |size=11
        |@Bucket.repo-name=test
        |creationTime=1496358655289
        |@BlobStore.content-type=text/plain
        |@BlobStore.blob-name=existing
        |sha1=eb4c2a5a1c04ca2d504c5e57e1f88cef08c75707
      """.stripMargin()

    fileMetadata = File.createTempFile('filemetadata', 'properties')
    fileMetadata << 'type=file/1'

    otherMetadata = File.createTempFile('othermetadata', 'properties')
    otherMetadata << 'type=other/2'
  }

  def cleanupSpec() {
    tempFileBytes.delete()
  }

  def setup() {
    storageFactory.create(_) >> storage
    config.name = 'GoogleCloudBlobStoreTest'
    config.attributes = [ 'google cloud storage': [
        bucketName: 'mybucket',
        region: 'us-central1'
    ] ]

    datastoreFactory.create(_) >> datastore
    datastore.newKeyFactory() >> keyFactory
    Transaction tx = Mock()
    datastore.newTransaction(_) >> tx
    datastore.run(_) >> emptyResults()
  }

  def 'initialize successfully from existing bucket'() {
    given: 'bucket exists'
      storage.get('mybucket') >> bucket

    when: 'init is called'
      blobStore.init(config)

    then: 'no attempt to create'
      0 * storage.create(!null)
  }

  def 'initialize successfully creating bucket'() {
    given: 'bucket does not exist'
      storage.get('mybucket') >> null

    when: 'init is called'
      blobStore.init(config)

    then: 'no attempt to create'
      1 * storage.create(!null)
  }

  def 'init migrates legacy attributes'() {
    given: 'bucket does not exist'
      def legacyConfig = new MockBlobStoreConfiguration()
      legacyConfig.name = 'GoogleCloudBlobStoreTest'
      legacyConfig.attributes = [ 'google cloud storage': [
            bucket: 'mybucket',
            location: 'us-central1',
            credential_file: '/path/to/some/file.json'
      ] ]
      storage.get('mybucket') >> null

    when: 'init is called'
      blobStore.init(legacyConfig)

    then: 'attributes are updated'
      legacyConfig.name == 'GoogleCloudBlobStoreTest'
      legacyConfig.attributes('google cloud storage').get('bucketName') == 'mybucket'
      legacyConfig.attributes('google cloud storage').get('region') == 'us-central1'
      legacyConfig.attributes('google cloud storage').get('credentialFilePath') == '/path/to/some/file.json'
  }

  def 'store a blob successfully'() {
    given: 'blobstore setup'
      storage.get('mybucket') >> bucket
      storage.testIamPermissions(*_) >> Collections.singletonList(true)

      blobStore.init(config)
      blobStore.doStart()
      storage.create(_, _, _) >> mockGoogleObject(tempFileBytes)

      BlobId id = new BlobId(UUID.randomUUID().toString())
      String resolved = blobIdLocationResolver.getLocation(id)
      Key key = new KeyFactory("fakeproject")
          .setKind(ShardedCounterMetricsStore.SHARD)
          .newKey(resolved)
      Entity entity = new Entity(FullEntity.newBuilder(key)
        .set("size", 1234L)
        .set("count", 10L)
        .build())

      datastore.get(_) >> entity

    when: 'call create'
      Blob blob = blobStore.create(new ByteArrayInputStream('hello world'.bytes), blobHeaders, id)

    then: 'blob stored'
      blob != null
  }

  def 'read blob inputstream'() {
    given: 'blobstore setup'
      storage.get('mybucket') >> bucket
      blobStore.init(config)
      blobStore.doStart()

      BlobId id = new BlobId(UUID.randomUUID().toString())
      String resolved = blobIdLocationResolver.getLocation(id)
      bucket.get("content/${resolved}.properties", _) >> mockGoogleObject(tempFileAttributes)
      bucket.get("content/${resolved}.bytes", _) >> mockGoogleObject(tempFileBytes)

    when: 'call create'
      Blob blob = blobStore.get(id)

    then: 'blob contains expected content'
      blob.getInputStream().text == 'some blob contents'
  }

  def 'expected getBlobIdStream behavior'() {
    given: 'bucket list returns a stream of blobs'
      storage.get('mybucket') >> bucket
      blobStore.init(config)
      blobStore.doStart()
      storage.testIamPermissions(*_) >> Collections.singletonList(true)
      def b1 = com.google.cloud.storage.BlobId.of('foo', 'notundercontent.txt')
      def b2 = com.google.cloud.storage.BlobId.of('foo',
          'content/vol-01/chap-08/c2fc8932-6fae-45b8-a6da-d955663844d2.properties')
      def b3 = com.google.cloud.storage.BlobId.of('foo',
          'content/vol-01/chap-08/c2fc8932-6fae-45b8-a6da-d955663844d2.bytes')
      def b4 = com.google.cloud.storage.BlobId.of('foo',
          'content/vol-02/chap-09/tmp$cdfe6d4f-5d95-4102-b174-65c07cf3a488.properties')
      def page = Mock(Page)

      bucket.list(BlobListOption.prefix(GoogleCloudBlobStore.CONTENT_PREFIX)) >> page
      page.iterateAll() >>
          [ mockGoogleObject(b1), mockGoogleObject(b2), mockGoogleObject(b3), mockGoogleObject(b4) ]

    when: 'getBlobIdStream called'
      Stream<BlobId> stream = blobStore.getBlobIdStream()

    then: 'stream includes the BlobIds from matching properties files'
      List<BlobId> list = stream.collect(Collectors.toList())
      list.size() == 2
      list.contains(new BlobId('c2fc8932-6fae-45b8-a6da-d955663844d2'))
      list.contains(new BlobId('tmp$cdfe6d4f-5d95-4102-b174-65c07cf3a488'))
  }

  def 'start will accept a metadata.properties originally created with file blobstore'() {
    given: 'metadata.properties comes from a file blobstore'
      storage.get('mybucket') >> bucket
      2 * bucket.get('metadata.properties', _) >> mockGoogleObject(fileMetadata)

    when: 'doStart is called'
      blobStore.init(config)
      blobStore.doStart()

    then: 'blobstore is started'
      notThrown(IllegalStateException)
  }

  def 'start rejects a metadata.properties containing something other than file or gcp type' () {
    given: 'metadata.properties comes from some unknown blobstore'
      storage.get('mybucket') >> bucket
      storage.get('mybucket') >> bucket
      2 * bucket.get('metadata.properties', _) >> mockGoogleObject(otherMetadata)

    when: 'doStart is called'
      blobStore.init(config)
      blobStore.doStart()

    then: 'blobstore fails to start'
      thrown(IllegalStateException)
  }

  def 'exists returns true when the blobId is present' () {
    given: 'blobstore setup'
      storage.get('mybucket') >> bucket
      blobStore.init(config)
      blobStore.doStart()
      BlobId id = new BlobId(UUID.randomUUID().toString())
      String resolved = blobIdLocationResolver.getLocation(id)
      bucket.get("content/${resolved}.properties", _) >> mockGoogleObject(tempFileAttributes)

    when: 'call exists'
      boolean exists = blobStore.exists(id)

    then: 'returns true'
      exists
  }

  def 'exists returns false when the blobId is not present' () {
    given: 'blobstore setup'
      storage.get('mybucket') >> bucket
      blobStore.init(config)
      blobStore.doStart()

    when: 'call exists'
      boolean exists = blobStore.exists(new BlobId(UUID.randomUUID().toString()))

    then: 'returns false'
      !exists
  }

  def 'exists throws BlobStoreException when IOException is thrown' () {
    given: 'blobstore setup'
      storage.get('mybucket') >> bucket
      blobStore.init(config)
      blobStore.doStart()
      BlobId id = new BlobId(UUID.randomUUID().toString())
      String resolved = blobIdLocationResolver.getLocation(id)
      bucket.get("content/${resolved}.properties", _) >> { throw new IOException("this is a test") }

    when: 'call exists'
      blobStore.exists(id)

    then: 'returns false'
      thrown(BlobStoreException.class)
  }

  private mockGoogleObject(File file) {
    com.google.cloud.storage.Blob blob = Mock()
    blob.reader() >> new DelegatingReadChannel(FileChannel.open(file.toPath()))
    blob.getContent() >> IOUtils.toByteArray(new FileInputStream(file))
    blob
  }

  private mockGoogleObject(com.google.cloud.storage.BlobId blobId) {
    def blob = Mock(com.google.cloud.storage.Blob)
    blob.getName() >> blobId.name
    blob.getBlobId() >> blobId
    blob
  }

  private QueryResults<Object> emptyResults() {
    return new QueryResults<Object>() {
      boolean hasNext() {
        return false
      }

      @Override
      Object next() {
        return null
      }

      @Override
      Class<?> getResultClass() {
        return null
      }

      @Override
      Cursor getCursorAfter() {
        return null
      }

      @Override
      int getSkippedResults() {
        return 0
      }

      @Override
      MoreResultsType getMoreResults() {
        return null
      }
    }
  }
}
