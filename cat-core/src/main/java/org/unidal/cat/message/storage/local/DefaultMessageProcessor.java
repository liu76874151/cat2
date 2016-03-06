package org.unidal.cat.message.storage.local;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.unidal.cat.message.MessageId;
import org.unidal.cat.message.storage.Block;
import org.unidal.cat.message.storage.BlockDumper;
import org.unidal.cat.message.storage.MessageProcessor;
import org.unidal.cat.message.storage.internals.DefaultBlock;
import org.unidal.cat.metric.Benchmark;
import org.unidal.cat.metric.Metric;
import org.unidal.lookup.annotation.Inject;
import org.unidal.lookup.annotation.Named;

import com.dianping.cat.message.spi.MessageTree;
import com.dianping.cat.message.spi.internal.DefaultMessageTree;

@Named(type = MessageProcessor.class, instantiationStrategy = Named.PER_LOOKUP)
public class DefaultMessageProcessor implements MessageProcessor {
	@Inject
	private BlockDumper m_dumper;

	private int m_index;

	private BlockingQueue<MessageTree> m_queue;

	private Map<String, Block> m_blocks = new HashMap<String, Block>();

	private AtomicBoolean m_enabled;

	@Override
	public String getName() {
		return getClass() + "-" + m_index;
	}

	@Override
	public void initialize(int index, BlockingQueue<MessageTree> queue) {
		m_index = index;
		m_queue = queue;
		m_enabled = new AtomicBoolean(true);
	}

	@Override
	public void run() {
		Benchmark benchmark = new Benchmark("MessageProcessor-" + m_index);
		Metric wm = benchmark.get("wait");
		Metric pm = benchmark.get("pack");
		DefaultMessageTree tree;

		try {
			while (m_enabled.get()) {
				wm.start();
				tree = (DefaultMessageTree) m_queue.poll(5, TimeUnit.MILLISECONDS);
				wm.end();

				if (tree != null) {
					MessageId id = MessageId.parse(tree.getMessageId());
					String domain = id.getDomain();
					int hour = id.getHour();
					Block block = m_blocks.get(domain);

					if (block == null) {
						block = new DefaultBlock(domain, hour);
						m_blocks.put(domain, block);
					}

					try {
						pm.start();
						block.pack(id, tree.getBuffer());

						if (block.isFull()) {
							block.finish();

							m_dumper.dump(block);
							m_blocks.put(domain, new DefaultBlock(domain, hour));
						}

						pm.end();
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
		} catch (InterruptedException e) {
			// ignore it
		}

		System.out.println(getClass().getSimpleName() + "-" + m_index + " is shutdown");
		benchmark.print();
	}

	@Override
	public void shutdown() {
		m_enabled.set(false);

		for (Block block : m_blocks.values()) {
			try {
				block.finish();

				m_dumper.dump(block);
			} catch (IOException e) {
				// ignore it
			}
		}

		m_blocks.clear();
	}
}