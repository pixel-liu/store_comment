package com.foodiego.handler;

import com.foodiego.dto.VoucherOrderDTO;
import com.foodiego.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.io.IOException;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class RabbitMQHandlerTest {
    @Mock IVoucherOrderService voucherOrderService;
    @Mock StringRedisTemplate stringRedisTemplate;
    @Mock Channel channel;
    @InjectMocks RabbitMQHandler handler;
    VoucherOrderDTO dto;
    Message message;

    @BeforeEach void setup() {
        dto = new VoucherOrderDTO(); dto.setId(12345L); dto.setUserId(1L); dto.setVoucherId(100L);
        MessageProperties properties = new MessageProperties(); properties.setDeliveryTag(42);
        message = new Message(new byte[0], properties);
    }

    @Test void successAcknowledgesOnce() throws Exception {
        handler.handleVoucherOrder(message, channel, dto);
        verify(voucherOrderService).handleVoucherOrder(dto);
        verify(channel).basicAck(42,false);
        verify(channel,never()).basicNack(anyLong(),anyBoolean(),anyBoolean());
    }

    @Test void transientFailureRetriesTransactionThenAcknowledges() throws Exception {
        doThrow(new IllegalStateException("temporary")).doNothing()
                .when(voucherOrderService).handleVoucherOrder(dto);
        handler.handleVoucherOrder(message,channel,dto);
        verify(voucherOrderService,times(2)).handleVoucherOrder(dto);
        verify(channel).basicAck(42,false);
        verify(channel,never()).basicNack(anyLong(),anyBoolean(),anyBoolean());
    }

    @Test void repeatedFailureIsBoundedAndRoutesToDeadLetterQueue() throws Exception {
        doThrow(new IllegalStateException("database unavailable"))
                .when(voucherOrderService).handleVoucherOrder(dto);
        handler.handleVoucherOrder(message,channel,dto);
        verify(voucherOrderService,times(4)).handleVoucherOrder(dto);
        verify(channel).basicNack(42,false,false);
        verify(channel,never()).basicAck(anyLong(),anyBoolean());
    }

    @Test void acknowledgementFailureNeverCompensatesCommittedOrder() throws Exception {
        doThrow(new IOException("connection lost")).when(channel).basicAck(42,false);
        assertThrows(IOException.class, () -> handler.handleVoucherOrder(message,channel,dto));
        verify(voucherOrderService,times(1)).handleVoucherOrder(dto);
        verify(channel,never()).basicNack(anyLong(),anyBoolean(),anyBoolean());
    }

    @Test void existingSuccessfulOrderIsNotCompensated() throws Exception {
        when(voucherOrderService.markOrderFailed(dto)).thenReturn(false);
        handler.handleDeadLetter(message,channel,dto);
        verifyNoInteractions(stringRedisTemplate);
        verify(channel).basicAck(42,false);
    }

    @Test void failureLedgerIsRecordedBeforeAtomicCompensation() throws Exception {
        when(voucherOrderService.markOrderFailed(dto)).thenReturn(true);
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class),anyList(),anyString(),anyString()))
                .thenReturn(1L);
        handler.handleDeadLetter(message,channel,dto);
        org.mockito.InOrder ordered = inOrder(voucherOrderService,stringRedisTemplate,channel);
        ordered.verify(voucherOrderService).markOrderFailed(dto);
        ordered.verify(stringRedisTemplate).execute(any(DefaultRedisScript.class),anyList(),eq("1"),eq("12345:100:1"));
        ordered.verify(channel).basicAck(42,false);
    }

    @Test void failedCompensationIsRequeuedWithoutDroppingMessage() throws Exception {
        when(voucherOrderService.markOrderFailed(dto)).thenReturn(true);
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class),anyList(),anyString(),anyString()))
                .thenThrow(new IllegalStateException("Redis unavailable"));
        handler.handleDeadLetter(message,channel,dto);
        verify(channel).basicNack(42,false,true);
        verify(channel,never()).basicAck(anyLong(),anyBoolean());
    }
}
