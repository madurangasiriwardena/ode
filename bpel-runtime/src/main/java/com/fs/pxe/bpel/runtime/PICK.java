/*
 * File:      $RCSfile$
 * Copyright: (C) 1999-2005 FiveSight Technologies Inc.
 *
 */
package com.fs.pxe.bpel.runtime;

import com.fs.pxe.bpel.common.CorrelationKey;
import com.fs.pxe.bpel.common.FaultException;
import com.fs.pxe.bpel.explang.EvaluationException;
import com.fs.pxe.bpel.o.OPickReceive;
import com.fs.pxe.bpel.o.OScope;
import com.fs.pxe.bpel.runtime.channels.FaultData;
import com.fs.pxe.bpel.runtime.channels.PickResponseChannel;
import com.fs.pxe.bpel.runtime.channels.PickResponseML;
import com.fs.pxe.bpel.runtime.channels.TerminationML;
import com.fs.utils.xsd.Duration;
import com.fs.utils.DOMUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;


/**
 * Template for the BPEL <code>pick</code> activity.
 */
class PICK extends ACTIVITY {
	private static final long serialVersionUID = 1L;

	private static final Log __log = LogFactory.getLog(PICK.class);

  private OPickReceive _opick;

  // if multiple alarms are set, this is the alarm the evaluates to
  // the shortest absolute time until firing.
  private OPickReceive.OnAlarm _alarm = null;

  public PICK(ActivityInfo self, ScopeFrame scopeFrame, LinkFrame linkFrame) {
    super(self, scopeFrame, linkFrame);
    _opick= (OPickReceive) self.o;
  }


  /**
   * @see com.fs.jacob.Abstraction#self()
   */
  public void self() {
    PickResponseChannel pickResponseChannel = newChannel(PickResponseChannel.class);
    Date timeout;
    Selector[] selectors;

    try {
      selectors = new Selector[_opick.onMessages.size()];
      int idx = 0;
      for (OPickReceive.OnMessage onMessage : _opick.onMessages) {
        CorrelationKey key = null;
        PartnerLinkInstance pLinkInstance = _scopeFrame.resolve(onMessage.partnerLink);
        if (onMessage.matchCorrelation == null && !_opick.createInstanceFlag) {
          // Adding a route for opaque correlation. In this particular case, correlation is done
          // on the epr session identifier.
          if (!getBpelRuntimeContext().isEndpointReferenceInitialized(pLinkInstance, true))
            throw new FaultException(_opick.getOwner().constants.qnCorrelationViolation,
                    "Endpoint reference for myRole on partner link " + onMessage.partnerLink + " has never been" +
                            "initialized even though it's necessary for opaque correlations to work.");
          String sessionId = getBpelRuntimeContext().fetchEndpointSessionId(pLinkInstance, true);
          key = new CorrelationKey(-1, new String[]{sessionId});
        } else if (onMessage.matchCorrelation != null) {
          if (!getBpelRuntimeContext().isCorrelationInitialized(_scopeFrame.resolve(onMessage.matchCorrelation)))
            throw new FaultException(_opick.getOwner().constants.qnCorrelationViolation, "Correlation not initialized.");

          key = getBpelRuntimeContext().readCorrelation(_scopeFrame.resolve(onMessage.matchCorrelation));

          assert key != null;
        }

        selectors[idx] = new Selector(idx, pLinkInstance, onMessage.operation.getName(), onMessage.operation.getOutput() == null, onMessage.messageExchangeId, key);
        idx++;
      }

      timeout = null;
      for (OPickReceive.OnAlarm onAlarm : _opick.onAlarms) {
        Date dt = onAlarm.forExpr != null
                ? offsetFromNow(getBpelRuntimeContext().getExpLangRuntime().evaluateAsDuration(onAlarm.forExpr, getEvaluationContext()))
                : getBpelRuntimeContext().getExpLangRuntime().evaluateAsDate(onAlarm.untilExpr, getEvaluationContext()).getTime();
        if (timeout == null || timeout.compareTo(dt) > 0) {
          timeout = dt;
          _alarm = onAlarm;
        }
      }
      getBpelRuntimeContext().select(pickResponseChannel, timeout, _opick.createInstanceFlag, selectors);
    } catch(FaultException e){
      FaultData fault = createFault(e.getQName(), _opick,e.getMessage());
      dpe(_opick.outgoingLinks);
      _self.parent.completed(fault, CompensationHandler.emptySet());
      return;
    } catch (EvaluationException e) {
      String msg = "Unexpected evaluation error evaluating alarm.";
      __log.error(msg, e);
      throw new InvalidProcessException(msg, e);
    }

    // Dead path all the alarms that have no chace of coming first.
    for (OPickReceive.OnAlarm oa : _opick.onAlarms) {
      if (!oa.equals(_alarm)) {
        dpe(oa.activity);
      }
    }

    instance(new WAITING(pickResponseChannel));
  }

  /**
   * Calculate a duration offset from right now.
   * @param duration the offset
   * @return the resulting date. 
   */
  private static Date offsetFromNow(Duration duration) {
    Calendar cal = Calendar.getInstance();
    duration.addTo(cal);
    return cal.getTime();
  }


  private class WAITING extends BpelAbstraction {
		private static final long serialVersionUID = 1L;
		
		private PickResponseChannel _pickResponseChannel;

    private WAITING(PickResponseChannel pickResponseChannel) {
      this._pickResponseChannel = pickResponseChannel;
    }

    public void self() {

      object(false, new PickResponseML(_pickResponseChannel) {
        private static final long serialVersionUID = -8237296827418738011L;

        public void onRequestRcvd(int selectorIdx, String mexId) {
          OPickReceive.OnMessage onMessage = _opick.onMessages.get(selectorIdx);

          // dead path the non-selected onMessage blocks.
          for (OPickReceive.OnMessage onmsg : _opick.onMessages) {
            if (!onmsg.equals(onMessage)) {
              dpe(onmsg.activity);
            }
          }

          // dead-path the alarm (if any)
          if (_alarm != null) {
            dpe(_alarm.activity);
          }

          FaultData fault;
          Element msgEl = getBpelRuntimeContext().getMyRequest(mexId);
          try {
            getBpelRuntimeContext().initializeVariable(_scopeFrame.resolve(onMessage.variable),msgEl);
          } catch (Exception ex) {
            __log.error(ex);
            throw new RuntimeException(ex);
          }
          try {
            for (OScope.CorrelationSet cset : onMessage.initCorrelations) {
              initializeCorrelation(_scopeFrame.resolve(cset), _scopeFrame.resolve(onMessage.variable));
            }
            if (onMessage.partnerLink.hasPartnerRole()) {
              // Trying to initialize partner epr based on a message-provided epr/session.
              Node fromEpr = getBpelRuntimeContext().getSourceEPR(mexId);
              if (fromEpr != null) {
                if (__log.isDebugEnabled())
                  __log.debug("Received callback EPR " + DOMUtils.domToString(fromEpr)
                          + " saving it on partner link " + onMessage.partnerLink.getName());
                getBpelRuntimeContext().writeEndpointReference(
                        _scopeFrame.resolve(onMessage.partnerLink), (Element) fromEpr);
              }
            }
          } catch (FaultException e) {
            fault = createFault(e.getQName(), onMessage);
            _self.parent.completed(fault, CompensationHandler.emptySet());
            dpe(onMessage.activity);
            return;
          }

          // activate 'onMessage' activity
          // Because we are done with all the DPE, we can simply re-use our control
          // channels for the child.
          ActivityInfo child = new ActivityInfo(genMonotonic(), onMessage.activity, _self.self, _self.parent);
          instance(createChild(child,_scopeFrame,_linkFrame));
        }

        public void onTimeout() {
          // Dead path all the onMessage activiites (the other alarms have already been DPE'ed)
          for (OPickReceive.OnMessage onMessage : _opick.onMessages) {
            dpe(onMessage.activity);
          }

          // Because we are done with all the DPE, we can simply re-use our control
          // channels for the child.
          ActivityInfo child = new ActivityInfo(genMonotonic(), _alarm.activity, _self.self, _self.parent);
          instance(createChild(child,_scopeFrame,_linkFrame));
        }

        public void onCancel() {
          _self.parent.completed(null, CompensationHandler.emptySet());
        }

      }.or(new TerminationML(_self.self) {
        private static final long serialVersionUID = 4399496341785922396L;

        public void terminate() {
          getBpelRuntimeContext().cancel(_pickResponseChannel);
          instance(WAITING.this);
        }
      }));
    }
  }
}
