/**
 * Copyright 2014 BlackBerry, Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
 */

package com.blackberry.logdriver.klogger;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.blackberry.krackle.MetricRegistrySingleton;
import com.blackberry.krackle.producer.Producer;
import com.codahale.metrics.Meter;

public class ServerSocketLogReader implements Runnable
{
	private static final Logger LOG = LoggerFactory.getLogger(ServerSocketLogReader.class);
	private static final Object producersLock = new Object();
	private static final Map<String, Producer> producers = new HashMap<>();
	private final int maxLine;
	private final Socket socket;
	private final Producer producer;
	private final boolean encodeTimestamp;
	private final boolean validateUTF8;
	private final Meter mBytesReceived;
	private final Meter mBytesReceivedTotal;
	private final Meter mLinesReceived;
	private final Meter mLinesReceivedTotal;

	public ServerSocketLogReader(Source source, Socket s) throws Exception
	{
		LOG.info("Created new {} for connection {}", this.getClass().getName(), s.getRemoteSocketAddress());

		socket = s;
		maxLine = source.getConf().getMaxLineLength();
		encodeTimestamp = source.getConf().isEncodeTimestamp();
		validateUTF8 = source.getConf().isValidateUtf8();

		String clientId = source.getConf().getClientId();
		String key = source.getConf().getKafkaKey();
		String topic = source.getTopic();

		MetricRegistrySingleton.getInstance().enableJmx();

		synchronized (producersLock)
		{
			String mapKey = clientId + "::" + topic + "::" + key;
			
			if (producers.containsKey(mapKey))
			{
				producer = producers.get(mapKey);
			} 
			else
			{
				producer = new Producer(source.getConf(), clientId, source.getTopic(), key, MetricRegistrySingleton.getInstance().getMetricsRegistry());				
				producers.put(mapKey, producer);
			}
		}

		mBytesReceived = MetricRegistrySingleton.getInstance().getMetricsRegistry().meter("klogger:topics:" + topic + ":bytes received");
		mBytesReceivedTotal = MetricRegistrySingleton.getInstance().getMetricsRegistry().meter("klogger:total:bytes received");
		mLinesReceived = MetricRegistrySingleton.getInstance().getMetricsRegistry().meter("klogger:topics:" + topic + ":lines received");
		mLinesReceivedTotal = MetricRegistrySingleton.getInstance().getMetricsRegistry().meter("klogger:total:lines received");
	}

	@Override
	public void run()
	{
		UTF8Validator utf8Validator = null;
		
		if (validateUTF8)
		{
			utf8Validator = new UTF8Validator();
		}

		byte[] buffer = new byte[maxLine];

		// Calculate send buffer size.
		// If we're validating UTF-8, then theoretically, each byte could be
		// replaced by the three byte replacement character. So the send buffer
		// needs to be triple the max line length.
		// If we're encoding the timestamp, then that adds 10 bytes.
		
		byte[] sendBytes;
		{
			int sendBufferSize = maxLine;
			
			if (validateUTF8)
			{
				sendBufferSize *= 3;
			}
			if (encodeTimestamp)
			{
				sendBufferSize += 10;
			}
			
			sendBytes = new byte[sendBufferSize];
		}
		
		ByteBuffer sendBuffer = ByteBuffer.wrap(sendBytes);

		int start = 0;
		int limit = 0;
		int newline = 0;
		int bytesRead = 0;

		try
		{
			InputStream in = socket.getInputStream();

			while (true)
			{
				// Try to fill the buffer
				bytesRead = in.read(buffer, start, maxLine - start);
				
				// LOG.trace("Read {} bytes", bytesRead);
				if (bytesRead == -1)
				{
					break;
				}
				
				mBytesReceived.mark(bytesRead);
				mBytesReceivedTotal.mark(bytesRead);

				limit = start + bytesRead;
				start = 0;

				// String bufferString = new String(buffer, 0, limit, "UTF-8");
				// LOG.info("buffer = {}", bufferString);
				// LOG.trace("start={}, limit={}", start, limit);
				// Find newlines
				
				while (true)
				{
					newline = -1;
					for (int i = start; i < limit; i++)
					{
						if (buffer[i] == '\n')
						{
							newline = i;
							break;
						}
					}
					
					// LOG.trace("Newline at {}", newline);

					// Found a newline
					if (newline >= 0)
					{
						mLinesReceived.mark();
						mLinesReceivedTotal.mark();//LOG.trace("Newline at {}", newline);

						// LOG.info("Sending (pos {}, len {}):{}", start, newline - start,
						// new String(buffer, start, newline - start, "UTF-8"));
						sendBuffer.clear();

						if (encodeTimestamp)
						{
							sendBuffer.put(new byte[]
							{
								(byte) 0xFE, 0x00
							});
							
							sendBuffer.putLong(System.currentTimeMillis());
						}

						if (validateUTF8)
						{
							utf8Validator.validate(buffer, start, newline - start);
							sendBuffer.put(utf8Validator.getResultBytes(), 0, utf8Validator.getResultBuffer().limit());
						} 
						else
						{
							sendBuffer.put(buffer, start, newline - start);
						}

						producer.send(sendBytes, 0, sendBuffer.position());

						start = newline + 1;
						continue;
						
					} // did not find a newline
					else
					{
						// LOG.info("No newline.  start={}, limit={}", start, limit);
						// if the buffer is full, send it all. Otherwise, do
						// nothing.
						
						if (start == 0 && limit == maxLine)
						{
							mLinesReceived.mark();
							mLinesReceivedTotal.mark();

							// LOG.info("Sending line with no newline");
							// LOG.info("Sending:{}", new String(buffer, 0, maxLine,
							// "UTF-8"));
							
							sendBuffer.clear();

							if (encodeTimestamp)
							{
								sendBuffer.put(new byte[]
								{
									(byte) 0xFE, 0x00
								});
								sendBuffer.putLong(System.currentTimeMillis());
							}

							if (validateUTF8)
							{
								utf8Validator.validate(buffer, 0, maxLine);
								sendBuffer.put(utf8Validator.getResultBytes(), 0, utf8Validator.getResultBuffer().limit());
							}
							else
							{
								sendBuffer.put(buffer, 0, maxLine);
							}

							producer.send(sendBytes, 0, sendBuffer.position());

							start = 0;
							limit = 0;
							break;
							
						} // if there is still data, then shift it to the start
						else
						{
							if (start > 0 && start < limit)
							{
								int toMove = limit - start;
								int moveSize;
								int done = 0;
								
								while (done < toMove)
								{
									moveSize = Math.min(start - done, limit - start);
									
									System.arraycopy(buffer, start, buffer, done, moveSize);
									
									done += moveSize;
									start += moveSize;									
								}

								start = toMove;
								limit = toMove;
								break;
								
							} // We used all the data
							else
							{
								if (start >= limit)
								{
									// LOG.info("All data was used.");
									start = 0;
									limit = 0;
									break;
								} 
								else
								{
									// start == 0, so move it to the limit for the next pass.
									start = limit;
									break;
								}
							}
						}
					}
				}

			}
		} 
		catch (Throwable t)
		{
			t.printStackTrace();
		} 
		finally
		{
			try
			{
				socket.close();
				// Producers never close. The number of producers is fairly small, so
				// this shouldn't be an issue.
				// We may need to fix that at some point.
				// producer.close();
			} 
			catch (IOException e)
			{
				e.printStackTrace();
			}
		}
	}
}