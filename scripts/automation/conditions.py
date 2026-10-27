"""Finite GUI wait predicates over one verified render snapshot, without expression evaluation."""
import layout


def properties(obj, selector):
    return {'selector': selector,
            'condition': {'enum': ['present', 'absent', 'visible', 'hidden', 'enabled', 'disabled', 'text_equals']},
            'text': {'type': 'string', 'maxLength': 4096},
            'timeout_ms': {'type': 'integer', 'minimum': 100, 'maximum': 30000},
            'stable_samples': {'type': 'integer', 'minimum': 1, 'maximum': 10}}


def definition(obj, selector, extra=None, required=()):
    result = obj({**properties(obj, selector), **(extra or {})}, ('selector', 'condition', *required))
    result['allOf'] = [{'if': {'properties': {'condition': {'const': 'text_equals'}}},
                        'then': {'required': ['text']}, 'else': {'not': {'required': ['text']}}}]
    return result


def evaluate(items, selector, condition, text=None):
    targets = layout.select(items, selector)
    result = {'matched': len(targets), 'satisfied': False}
    if condition == 'absent':
        return {**result, 'satisfied': not targets}
    if len(targets) > 1:
        return {**result, 'error_code': 'AMBIGUOUS_ELEMENT', 'reason': 'Wait selector must identify exactly one node'}
    if not targets:
        return {**result, 'reason': 'node_missing'}
    item = targets[0]
    result['element'] = item
    if condition == 'present':
        result['satisfied'] = True
    elif condition in ('enabled', 'disabled'):
        result['satisfied'] = item['enabled'] == (condition == 'enabled')
    elif condition == 'text_equals':
        result['satisfied'] = item.get('text') == text
    elif condition in ('visible', 'hidden'):
        polygon = item.get('visible_polygon')
        if item.get('geometry_precision') != 'engine_gui_rectangle' or polygon is None:
            result.update(error_code='GEOMETRY_UNAVAILABLE', reason='Visibility requires verified GUI rectangle geometry')
        else:
            visible = abs(layout.area(polygon)) > 0
            result['satisfied'] = visible == (condition == 'visible')
    else:
        raise ValueError('Unknown finite wait condition')
    return result


class Stability:
    """Distinct sampled renders of the same node/device count; replacement and mismatch reset the streak."""
    def __init__(self):
        self.sequence = None
        self.identity = None
        self.count = 0

    def update(self, frame, evaluation):
        sequence = (frame['device_revision'], frame['render_sequence'])
        if sequence == self.sequence:
            return self.count
        self.sequence = sequence
        identity = (frame['device_revision'], evaluation.get('element', {}).get('element_id'))
        if evaluation['satisfied']:
            self.count = self.count + 1 if self.identity == identity else 1
            self.identity = identity
        else:
            self.count = 0
            self.identity = None
        return self.count
