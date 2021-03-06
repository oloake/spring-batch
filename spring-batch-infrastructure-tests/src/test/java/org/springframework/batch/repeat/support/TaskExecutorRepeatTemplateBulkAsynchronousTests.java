/*
 * Copyright 2006-2007 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.batch.repeat.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.batch.repeat.RepeatCallback;
import org.springframework.batch.repeat.RepeatContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.repeat.policy.SimpleCompletionPolicy;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Simple tests for concurrent behaviour in repeat template, in particular the
 * barrier at the end of the iteration. N.B. these tests may fail if
 * insufficient threads are available (e.g. on a single-core machine, or under
 * load). They shouldn't deadlock though.
 * 
 * @author Dave Syer
 * 
 */
public class TaskExecutorRepeatTemplateBulkAsynchronousTests {

	static Log logger = LogFactory.getLog(TaskExecutorRepeatTemplateBulkAsynchronousTests.class);

	private int total = 1000;

	private int throttleLimit = 30;

	private volatile int early = Integer.MAX_VALUE;

	private TaskExecutorRepeatTemplate template;

	private RepeatCallback callback;

	private List<String> items;

	private ThreadPoolTaskExecutor taskExecutor;

	@Before
	public void setUp() {

		template = new TaskExecutorRepeatTemplate();
		taskExecutor = new ThreadPoolTaskExecutor();
		taskExecutor.setMaxPoolSize(300);
		taskExecutor.setCorePoolSize(10);
		taskExecutor.setQueueCapacity(0);
		taskExecutor.afterPropertiesSet();
		template.setTaskExecutor(taskExecutor);
		template.setThrottleLimit(throttleLimit);

		items = Collections.synchronizedList(new ArrayList<String>());

		callback = new RepeatCallback() {

			private volatile AtomicInteger count = new AtomicInteger(0);

			public RepeatStatus doInIteration(RepeatContext context) throws Exception {
				int position = count.incrementAndGet();
				String item = position <= total ? "" + position : null;
				items.add("" + item);
				if (item != null) {
					beBusy();
				}
				/*
				 * In a multi-threaded task, one of the callbacks can call
				 * FINISHED early, while other threads are still working, and
				 * would do more work if the callback was called again. (This
				 * happens for instance if there is a failure and you want to
				 * retry the work.)
				 */
				RepeatStatus result = RepeatStatus.continueIf(position != early && item != null);
				if (!result.isContinuable()) {
					logger.debug("Returning " + result + " for count=" + position);
				}
				return result;
			}
		};

	}

	@Test
	public void testThrottleLimit() throws Exception {

		template.iterate(callback);
		int frequency = Collections.frequency(items, "null");
		// System.err.println(items);
		// System.err.println("Frequency: " + frequency);
		assertEquals(total, items.size() - frequency);
		assertTrue(frequency > 1);
		assertTrue(frequency <= throttleLimit + 1);

	}

	@After
	public void tearDown() {
		taskExecutor.destroy();
	}

	@Test
	public void testThrottleLimitEarlyFinish() throws Exception {

		early = 2;

		template.iterate(callback);
		int frequency = Collections.frequency(items, "null");
		// System.err.println("Frequency: " + frequency);
		// System.err.println("Items: " + items);
		assertEquals(total, items.size() - frequency);
		assertTrue(frequency > 1);
		assertTrue(frequency <= throttleLimit + 1);

	}

	@Test
	public void testThrottleLimitEarlyFinishThreadStarvation() throws Exception {

		early = 2;
		ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
		// Set the concurrency limit below the throttle limit for possible
		// starvation condition
		taskExecutor.setMaxPoolSize(20);
		taskExecutor.setCorePoolSize(10);
		taskExecutor.setQueueCapacity(0);
		// This is the most sensible setting, otherwise the bookkeeping in
		// ResultHolderResultQueue gets out of whack when tasks are aborted.
		taskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
		taskExecutor.afterPropertiesSet();
		template.setTaskExecutor(taskExecutor);

		template.iterate(callback);
		int frequency = Collections.frequency(items, "null");
		// System.err.println("Frequency: " + frequency);
		// System.err.println("Items: " + items);
		// Extra tasks will be submitted before the termination is detected
		assertEquals(total, items.size() - frequency);
		assertTrue(frequency <= throttleLimit + 1);
		
		taskExecutor.destroy();

	}

	@Test
	public void testThrottleLimitEarlyFinishOneThread() throws Exception {

		early = 4;
		SimpleAsyncTaskExecutor taskExecutor = new SimpleAsyncTaskExecutor();
		taskExecutor.setConcurrencyLimit(1);

		// This is kind of slow with only one thread, so reduce size:
		throttleLimit = 10;
		total = 20;

		template.setThrottleLimit(throttleLimit);
		template.setTaskExecutor(taskExecutor);

		template.iterate(callback);
		int frequency = Collections.frequency(items, "null");
		// System.err.println("Frequency: " + frequency);
		// System.err.println("Items: " + items);
		assertEquals(total, items.size() - frequency);
		assertTrue(frequency <= throttleLimit + 1);

	}

	@Test
	public void testThrottleLimitWithEarlyCompletion() throws Exception {

		early = 2;
		template.setCompletionPolicy(new SimpleCompletionPolicy(10));

		template.iterate(callback);
		int frequency = Collections.frequency(items, "null");
		assertEquals(10, items.size() - frequency);
		// System.err.println("Frequency: " + frequency);
		assertEquals(0, frequency);

	}

	/**
	 * Slightly flakey convenience method. If this doesn't do something that
	 * lasts sufficiently long for another worker to be launched while it is
	 * busy, the early completion tests will fail. "Sufficiently long" is the
	 * problem so we try and block until we know someone else is busy?
	 * 
	 * @throws Exception
	 */
	private void beBusy() throws Exception {
		synchronized (this) {
			wait(100L);
			notifyAll();
		}
	}

}
